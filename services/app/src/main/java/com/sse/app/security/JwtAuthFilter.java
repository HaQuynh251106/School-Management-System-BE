package com.sse.app.security;

import com.sse.app.identity.*;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;
    private final RefreshTokenRepository sessions;
    private final RbacService rbac;
    private final UserService userService;

    public JwtAuthFilter(JwtService jwt, UserRepository users,
                         RefreshTokenRepository sessions, RbacService rbac,
                         UserService userService) {
        this.jwt = jwt;
        this.users = users;
        this.sessions = sessions;
        this.rbac = rbac;
        this.userService = userService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || path.startsWith("/auth/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.matches("^/payments/[^/]+/(ipn|callback|return)$")
                || path.equals("/")
                || path.startsWith("/health")
                || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing token");
            return;
        }
        try {
            Claims claims = jwt.parse(header.substring(7).trim());
            if (!"access".equals(claims.get("type", String.class))) {
                error(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid access token");
                return;
            }
            User user = users.findById(claims.getSubject()).orElse(null);
            if (user == null || !"ACTIVE".equals(user.getStatus())
                    || user.getDeletedAt() != null) {
                error(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Account is not active");
                return;
            }

            Number versionClaim = claims.get("sv", Number.class);
            String sessionId = claims.get("sid", String.class);
            if (versionClaim == null || sessionId == null
                    || versionClaim.intValue() != user.getSessionVersion()) {
                error(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Session has been revoked");
                return;
            }
            RefreshToken session = sessions
                    .findByIdAndUserIdAndRevokedAtIsNull(sessionId, user.getId())
                    .orElse(null);
            if (session == null || session.getExpiresAt().isBefore(Instant.now())
                    || session.getSessionVersion() != user.getSessionVersion()) {
                error(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Session has been revoked");
                return;
            }

            CurrentUser current = new CurrentUser(
                    user.getId(), user.getUsername(), user.getRole(),
                    rbac.permissionsFor(user.getId()),
                    user.isPasswordChangeRequired(),
                    user.getSessionVersion(), sessionId);
            CurrentUserHolder.set(current);
            if (current.passwordChangeRequired()
                    && !isPasswordChangePath(request)) {
                error(response, HttpServletResponse.SC_FORBIDDEN,
                        "PASSWORD_CHANGE_REQUIRED");
                return;
            }
            userService.touchSession(user.getId(), sessionId);
            chain.doFilter(request, response);
        } catch (Exception exception) {
            if (!response.isCommitted()) {
                error(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
            }
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private boolean isPasswordChangePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/me")
                || (path.equals("/me/password") && HttpMethod.PUT.matches(request.getMethod()));
    }

    private void error(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
