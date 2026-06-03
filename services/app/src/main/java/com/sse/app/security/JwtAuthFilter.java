package com.sse.app.security;

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

/**
 * Xác thực JWT cho mọi request trừ /auth/** và preflight OPTIONS — khớp hành vi mock-server.
 * Hợp lệ → set {@link CurrentUserHolder}; thiếu/không hợp lệ → 401 {"error": "..."}.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || p.startsWith("/auth/")
                || p.equals("/")
                || p.equals("/health")
                || p.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(res, "Missing token");
            return;
        }
        String token = header.substring(7).trim();
        try {
            Claims c = jwt.parse(token);
            CurrentUserHolder.set(new CurrentUser(
                    c.getSubject(),
                    c.get("username", String.class),
                    c.get("role", String.class)));
            chain.doFilter(req, res);
        } catch (Exception e) {
            unauthorized(res, "Invalid token");
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void unauthorized(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
