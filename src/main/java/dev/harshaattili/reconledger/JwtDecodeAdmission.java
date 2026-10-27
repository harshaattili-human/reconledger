package dev.harshaattili.reconledger;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Nonblocking admission before parsing, cache-lock waiting or signature validation. */
final class JwtDecodeAdmission implements JwtDecoder {
    private final JwtDecoder delegate;
    private final Semaphore permits;

    JwtDecodeAdmission(JwtDecoder delegate, int capacity) {
        if (capacity < 1 || capacity > 1000) {
            throw new IllegalArgumentException("recon.security.max-concurrent-decodes must be 1..1000");
        }
        this.delegate = Objects.requireNonNull(delegate);
        permits = new Semaphore(capacity);
    }

    @Override public Jwt decode(String token) {
        if (!permits.tryAcquire()) {
            // Infrastructure unavailability, not BadJwtException/invalid-token authentication.
            throw new JwtException("Token verification capacity unavailable");
        }
        try {
            return delegate.decode(token);
        } finally {
            permits.release();
        }
    }
}
