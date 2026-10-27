package dev.harshaattili.reconledger;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/** Keep verification infrastructure failures out of servlet exception responses/logs. */
final class BearerFailureHandler implements AuthenticationFailureHandler {
    private final BearerTokenAuthenticationEntryPoint invalidToken = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException, ServletException {
        if (failure instanceof AuthenticationServiceException) {
            // Do not echo/log the exception: remote URI, body and nested messages are untrusted.
            // The outer request diagnostic already records a bounded status/outcome event.
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Token verification unavailable\",\"status\":503}");
            return;
        }
        invalidToken.commence(request, response, failure);
    }
}
