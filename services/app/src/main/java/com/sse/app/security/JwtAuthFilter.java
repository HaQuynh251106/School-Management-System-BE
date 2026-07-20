package com.sse.app.security;

import com.sse.app.identity.UserService;
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
    private final UserService users;

    public JwtAuthFilter(JwtService jwt, UserService users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || p.startsWith("/auth/")
                || p.startsWith("/payments/callback/")
                || p.equals("/payments/vnpay/ipn")
                || p.equals("/")
                || p.equals("/health")
                || p.equals("/actuator/health")
                || p.equals("/actuator/info")
                || p.startsWith("/v3/api-docs")
                || p.startsWith("/swagger-ui");
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
            var account = users.getById(c.getSubject());
            Integer tokenVersion = c.get("ver", Integer.class);
            if (tokenVersion == null || tokenVersion != account.getTokenVersion()) {
                unauthorized(res, "Phiên đăng nhập đã hết hiệu lực");
                return;
            }
            if (account.isPasswordChangeRequired()
                    && !req.getRequestURI().equals("/me")
                    && !req.getRequestURI().equals("/me/password")) {
                forbidden(res, "Bạn cần đổi mật khẩu trước khi tiếp tục");
                return;
            }
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

    private void forbidden(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
