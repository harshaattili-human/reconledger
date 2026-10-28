package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class JwksCacheDecoderTest {
    @Test void expiredKnownKeyRefetchesWithoutNewKidAndOutageNeverUsesExpiredKeys() throws Exception {
        var a = key();
        var b = key();
        var body = new AtomicReference<>(jwks("a", a));
        var status = new AtomicInteger(200);
        var fetches = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            var now = new AtomicLong();
            var decoder = NimbusJwtDecoder.withJwkSetUri(uri).restOperations(JwksHttp.create())
                .cache(new JwksCache(uri, 300, now::get)).build();
            String tokenA = token("a", a), tokenB = token("b", b);
            assertThat(decoder.decode(tokenA).getSubject()).isEqualTo("synthetic");
            int warmed = fetches.get();
            body.set(jwks("b", b));
            now.set(TimeUnit.SECONDS.toNanos(300) - 1);
            assertThat(decoder.decode(tokenA).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(warmed);
            now.incrementAndGet();
            // Same token/kid is submitted first after expiry. No B token triggers refresh.
            assertThatThrownBy(() -> decoder.decode(tokenA)).isInstanceOf(BadJwtException.class);
            assertThat(fetches.get()).isGreaterThan(warmed);
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
            int refreshed = fetches.get();
            status.set(503);
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(refreshed);
            now.addAndGet(TimeUnit.SECONDS.toNanos(300));
            assertThatThrownBy(() -> decoder.decode(tokenB)).isInstanceOf(JwtException.class)
                .isNotInstanceOf(BadJwtException.class);
            assertThat(fetches.get()).isGreaterThan(refreshed);
            status.set(200);
            body.set("malformed synthetic JWKS");
            assertThatThrownBy(() -> decoder.decode(tokenB)).isInstanceOf(JwtException.class);
            body.set(jwks("b", b));
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
            // Unknown-kid refresh explicitly invalidates a custom Spring cache. A
            // failed refresh does not preserve even a previously fresh cached key.
            status.set(503);
            assertThatThrownBy(() -> decoder.decode(tokenA)).isInstanceOf(JwtException.class);
            int unknownFailed = fetches.get();
            assertThatThrownBy(() -> decoder.decode(tokenB)).isInstanceOf(JwtException.class);
            assertThat(fetches.get()).isGreaterThan(unknownFailed);
            status.set(200);
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
        } finally { server.stop(0); }
    }

    private static KeyPair key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
    private static String jwks(String kid, KeyPair pair) {
        return "{\"keys\":[" + new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .keyID(kid).algorithm(JWSAlgorithm.RS256).build().toJSONString() + "]}";
    }
    private static String token(String kid, KeyPair pair) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
            new JWTClaimsSet.Builder().subject("synthetic")
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).build());
        jwt.sign(new RSASSASigner(pair.getPrivate()));
        return jwt.serialize();
    }
}
