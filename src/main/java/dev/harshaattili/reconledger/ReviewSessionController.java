package dev.harshaattili.reconledger;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only display context; every data request still passes normal authorization. */
@RestController
public class ReviewSessionController {
    private final boolean secured;
    private final TenantAccess tenants;

    public ReviewSessionController(Environment environment, TenantAccess tenants) {
        this.secured = environment.acceptsProfiles(Profiles.of("secured"));
        this.tenants = tenants;
    }

    @GetMapping("/api/review-session")
    ResponseEntity<Session> session(Authentication authentication) {
        Session session;
        if (!secured) {
            session = new Session(false, "demo", null, null, true);
        } else if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            boolean canReview = token.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("SCOPE_recon:review"));
            session = new Session(true, tenants.current(), token.getToken().getSubject(),
                token.getToken().getClaimAsString("iss"), canReview);
        } else {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A verified reviewer identity is required.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
    }

    record Session(boolean secured, String tenant, String subject, String issuer, boolean canReview) {}
}
