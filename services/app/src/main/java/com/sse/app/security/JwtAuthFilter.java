package com.sse.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.common.ApiErrorResponse;
import com.sse.app.common.RequestCorrelationFilter;
import com.sse.app.identity.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Xác thực JWT cho mọi request trừ /auth/** và preflight OPTIONS — khớp hành vi mock-server.
 * Hợp lệ → set {@link CurrentUserHolder}; thiếu/không hợp lệ → 401 {"error": "..."}.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserService users;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwt, UserService users, ObjectMapper objectMapper) {
        this.jwt = jwt;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || p.startsWith("/auth/")
                || p.equals("/")
                || p.equals("/health")
                || p.equals("/actuator/health")
                || p.equals("/actuator/info")
                || p.equals("/actuator/prometheus")
                || p.startsWith("/v3/api-docs")
                || p.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            error(req, res, HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_MISSING", "Phiên đăng nhập không tồn tại");
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
                error(req, res, HttpStatus.UNAUTHORIZED, "AUTH_SESSION_EXPIRED", "Phiên đăng nhập đã hết hiệu lực");
                return;
            }
            if (account.isPasswordChangeRequired()
                    && !req.getRequestURI().equals("/me")
                    && !req.getRequestURI().equals("/me/password")) {
                error(req, res, HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                        "Bạn cần đổi mật khẩu trước khi tiếp tục");
                return;
            }
            chain.doFilter(req, res);
        } catch (Exception e) {
            error(req, res, HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID",
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void error(
            HttpServletRequest req,
            HttpServletResponse res,
            HttpStatus status,
            String code,
            String message
    ) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.setHeader(RequestCorrelationFilter.HEADER, RequestCorrelationFilter.currentId(req));
        objectMapper.writeValue(res.getWriter(),
                ApiErrorResponse.of(status, code, message, req, Map.of()));
    }
}
