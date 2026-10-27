package dev.harshaattili.reconledger;

import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class ApiSecurity {
    @Bean
    @Profile("!secured")
    SecurityFilterChain localDemo(HttpSecurity http) throws Exception {
        return stateless(http).authorizeHttpRequests(rules -> rules.anyRequest().permitAll()).build();
    }

    @Bean
    @Profile("secured")
    SecurityFilterChain authenticatedApi(HttpSecurity http,
            @Value("${recon.security.review-ui-enabled:false}") boolean reviewUiEnabled) throws Exception {
        return stateless(http)
            .authorizeHttpRequests(rules -> {
                if (reviewUiEnabled) {
                    rules.requestMatchers(HttpMethod.GET, "/review/index.html",
                        "/review/review.js", "/review/review.css").permitAll();
                }
                rules
                // Keep MVC error responses (400/404/413) from becoming a second authorization failure.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/review-session", "/api/batches", "/api/batches/*",
                    "/api/results/*", "/api/results/*/events").hasAuthority("SCOPE_recon:read")
                .requestMatchers(HttpMethod.POST, "/api/batches").hasAuthority("SCOPE_recon:write")
                .requestMatchers(HttpMethod.POST, "/api/results/*/reviews").hasAuthority("SCOPE_recon:review")
                .anyRequest().denyAll();
            })
            .oauth2ResourceServer(server -> {
                server.jwt(Customizer.withDefaults());
                server.addObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                    @Override
                    public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                        filter.setAuthenticationFailureHandler(new BearerFailureHandler());
                        return filter;
                    }
                });
            })
            .build();
    }

    private HttpSecurity stateless(HttpSecurity http) throws Exception {
        // Only explicit bearer headers authenticate secured requests; no cookie or Basic login.
        return http.csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    @Bean
    @Profile("secured")
    JwtDecoder jwtDecoder(@Value("${recon.security.public-key:}") String publicKeyLocation,
                          @Value("${recon.security.jwk-set-uri:}") String jwkSetUri,
                          @Value("${recon.security.issuer}") String issuer,
                          @Value("${recon.security.audience}") String audience,
                          ResourceLoader resources) throws IOException {
        validateClaimsConfiguration(issuer, audience);
        boolean hasPublicKey = publicKeyLocation != null && !publicKeyLocation.isBlank();
        boolean hasJwks = jwkSetUri != null && !jwkSetUri.isBlank();
        Assert.isTrue(hasPublicKey != hasJwks,
            "Configure exactly one of recon.security.public-key or recon.security.jwk-set-uri");
        NimbusJwtDecoder decoder;
        if (hasPublicKey) {
            decoder = pinnedDecoder(resources.getResource(publicKeyLocation));
        } else {
            validateJwkSetUri(jwkSetUri);
            decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(JwksHttp.create()).build();
        }
        configureValidation(decoder, issuer, audience);
        return decoder;
    }

    /** Retained as a narrow construction seam for pinned-key configuration tests. */
    JwtDecoder jwtDecoder(Resource publicKey, String issuer, String audience) throws IOException {
        validateClaimsConfiguration(issuer, audience);
        var decoder = pinnedDecoder(publicKey);
        configureValidation(decoder, issuer, audience);
        return decoder;
    }

    private NimbusJwtDecoder pinnedDecoder(Resource publicKey) throws IOException {
        RSAPublicKey key;
        try (var input = publicKey.getInputStream()) {
            key = RsaKeyConverters.x509().convert(input);
        }
        Assert.notNull(key, "An X.509 PEM RSA public key is required");
        Assert.isTrue(key.getModulus().bitLength() >= 2048, "RSA public key must be at least 2048 bits");
        return NimbusJwtDecoder.withPublicKey(key).build(); // RS256 only.
    }

    private void validateClaimsConfiguration(String issuer, String audience) {
        Assert.hasText(issuer, "recon.security.issuer must not be blank");
        Assert.isTrue(issuer.length() <= 2048, "recon.security.issuer must be at most 2048 characters");
        Assert.hasText(audience, "recon.security.audience must not be blank");
    }

    private void configureValidation(NimbusJwtDecoder decoder, String issuer, String audience) {
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
            JwtValidators.createDefaultWithIssuer(issuer), token -> {
                String subject = token.getSubject();
                var audiences = token.getAudience();
                if (token.getExpiresAt() == null || audiences == null || !audiences.contains(audience)
                    || subject == null || subject.isBlank() || subject.length() > 80
                    || !subject.equals(subject.strip())
                    || !TenantAccess.validClaim(token.getClaims().get("tenant_id"))) {
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "A valid audience, expiry, subject and tenant are required", null));
                }
                return OAuth2TokenValidatorResult.success();
            }));
    }

    private static void validateJwkSetUri(String value) {
        Assert.isTrue(value.length() <= 2048, "recon.security.jwk-set-uri must be at most 2048 characters");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("recon.security.jwk-set-uri must be an absolute HTTPS URI", failure);
        }
        String host = uri.getHost();
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
            && ("127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host));
        Assert.isTrue("https".equalsIgnoreCase(uri.getScheme()) || loopbackHttp,
            "recon.security.jwk-set-uri must use HTTPS (HTTP is allowed only for loopback verification)");
        Assert.hasText(host, "recon.security.jwk-set-uri must include a host");
        Assert.isTrue(uri.getUserInfo() == null && uri.getFragment() == null,
            "recon.security.jwk-set-uri must not include user information or a fragment");
    }
}
