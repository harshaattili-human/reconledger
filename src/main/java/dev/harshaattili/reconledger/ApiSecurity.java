package dev.harshaattili.reconledger;

import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
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
import org.springframework.security.web.SecurityFilterChain;
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
    SecurityFilterChain authenticatedApi(HttpSecurity http) throws Exception {
        return stateless(http)
            .authorizeHttpRequests(rules -> rules
                // Keep MVC error responses (400/404/413) from becoming a second authorization failure.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/batches", "/api/batches/*",
                    "/api/results/*", "/api/results/*/events").hasAuthority("SCOPE_recon:read")
                .requestMatchers(HttpMethod.POST, "/api/batches").hasAuthority("SCOPE_recon:write")
                .requestMatchers(HttpMethod.POST, "/api/results/*/reviews").hasAuthority("SCOPE_recon:review")
                .anyRequest().denyAll())
            .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
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
    JwtDecoder jwtDecoder(@Value("${recon.security.public-key}") Resource publicKey,
                          @Value("${recon.security.issuer}") String issuer,
                          @Value("${recon.security.audience}") String audience) throws IOException {
        Assert.hasText(issuer, "recon.security.issuer must not be blank");
        Assert.hasText(audience, "recon.security.audience must not be blank");
        RSAPublicKey key;
        try (var input = publicKey.getInputStream()) {
            key = RsaKeyConverters.x509().convert(input);
        }
        Assert.notNull(key, "An X.509 PEM RSA public key is required");
        Assert.isTrue(key.getModulus().bitLength() >= 2048, "RSA public key must be at least 2048 bits");
        var decoder = NimbusJwtDecoder.withPublicKey(key).build(); // RS256 only.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
            JwtValidators.createDefaultWithIssuer(issuer), token -> {
                String subject = token.getSubject();
                var audiences = token.getAudience();
                if (token.getExpiresAt() == null || audiences == null || !audiences.contains(audience)
                    || subject == null || subject.isBlank() || subject.length() > 80
                    || !subject.equals(subject.strip())) {
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "A valid audience, expiry and bounded subject are required", null));
                }
                return OAuth2TokenValidatorResult.success();
            }));
        return decoder;
    }
}
