package com.sse.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Keeps legacy web routes working while exposing the same contract under /api/v1. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiVersionPrefixFilter extends OncePerRequestFilter {
    static final String PREFIX = "/api/v1";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals(PREFIX) || uri.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String originalUri = request.getRequestURI();
        String strippedUri = originalUri.substring(PREFIX.length());
        if (strippedUri.isBlank()) strippedUri = "/";
        String finalUri = strippedUri;
        HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(request) {
            @Override public String getRequestURI() { return finalUri; }
            @Override public String getServletPath() { return finalUri; }
            @Override public String getPathInfo() { return finalUri; }
        };
        response.setHeader("X-API-Version", "v1");
        filterChain.doFilter(wrapper, response);
    }
}
