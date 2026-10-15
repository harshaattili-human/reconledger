package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ReviewIdentityTest {
    private final ReviewInput input = new ReviewInput(ReviewState.IN_REVIEW, 0, null, "Synthetic note");

    @Test void keepsIssuerNamespaceWhenSubjectsAreEqual() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("secured");
        var identity = new ReviewIdentity(environment);
        // Trusted in-process fixtures; signature validation belongs to the HTTP tests.
        for (String issuer : List.of("https://Issuer.example.invalid/A", "https://issuer.example.invalid/B")) {
            var jwt = Jwt.withTokenValue("test-only").header("alg", "RS256")
                .subject("same-subject").claim("iss", issuer).build();
            var resolved = identity.resolve(input, new JwtAuthenticationToken(jwt, List.of()));
            assertThat(resolved.actor()).isEqualTo("same-subject");
            assertThat(resolved.actorSource()).isEqualTo(ActorSource.VERIFIED_JWT);
            assertThat(resolved.actorIssuer()).isEqualTo(issuer);
        }
        var demo = new ReviewIdentity(new MockEnvironment()).resolve(
            new ReviewInput(ReviewState.IN_REVIEW, 0, " same-subject ", input.note()), null);
        assertThat(demo.actor()).isEqualTo("same-subject");
        assertThat(demo.actorSource()).isEqualTo(ActorSource.DEMO_LABEL);
        assertThat(demo.actorIssuer()).isNull();
    }

    @Test void missingOrInvalidTrustedContextCannotProduceVerifiedProvenance() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("secured");
        var identity = new ReviewIdentity(environment);
        assertThatThrownBy(() -> identity.resolve(input, null)).isInstanceOf(ApiException.class);
        for (Object issuer : List.of("", " ", "x".repeat(2049), 42)) {
            var jwt = Jwt.withTokenValue("test-only").header("alg", "RS256")
                .subject("subject").claim("iss", issuer).build();
            assertThatThrownBy(() -> identity.resolve(input, new JwtAuthenticationToken(jwt, List.of())))
                .isInstanceOf(ApiException.class);
        }
        var noIssuer = Jwt.withTokenValue("test-only").header("alg", "RS256").subject("subject").build();
        assertThatThrownBy(() -> identity.resolve(input, new JwtAuthenticationToken(noIssuer, List.of())))
            .isInstanceOf(ApiException.class);
    }
}
