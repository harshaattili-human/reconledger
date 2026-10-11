package dev.harshaattili.reconledger;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class TenantAccess {
    private final boolean secured;

    public TenantAccess(Environment environment) {
        secured = environment.acceptsProfiles(Profiles.of("secured"));
    }

    static boolean validClaim(Object claim) {
        return claim instanceof String tenant && tenant.matches("[a-z0-9][a-z0-9_-]{0,63}")
            && !tenant.equals("demo");
    }

    String current() {
        if (!secured) return "demo";
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            Object tenant = token.getToken().getClaims().get("tenant_id");
            if (validClaim(tenant)) return (String) tenant;
        }
        // No fallback to demo when a secured call has lost its authentication context.
        throw new ApiException(HttpStatus.UNAUTHORIZED, "A verified tenant identity is required.");
    }
}
