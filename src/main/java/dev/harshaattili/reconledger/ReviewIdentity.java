package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.ReviewInput;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class ReviewIdentity {
    private final boolean secured;

    public ReviewIdentity(Environment environment) {
        secured = environment.acceptsProfiles(Profiles.of("secured"));
    }

    ReviewInput resolve(ReviewInput input, Authentication authentication) {
        if (!secured) {
            if (input.actor() == null || input.actor().isBlank()) {
                throw ApiException.badRequest("actor is required in the local demo.");
            }
            return input;
        }
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "An authenticated reviewer is required.");
        }
        if (input.actor() != null) {
            throw ApiException.badRequest("Omit actor in secured mode; reviewer identity comes from the token.");
        }
        return new ReviewInput(input.targetState(), input.expectedVersion(), token.getName(), input.note());
    }
}
