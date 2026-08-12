package com.sse.app.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiVersionPrefixFilterTest {
    @Test
    void stripsV1PrefixBeforeControllerAndSecurityMapping() throws Exception {
        ApiVersionPrefixFilter filter = new ApiVersionPrefixFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/page");
        request.setServletPath("/api/v1/notifications/page");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenUri = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenUri.set(((jakarta.servlet.http.HttpServletRequest) req).getRequestURI());

        filter.doFilter(request, response, chain);

        assertEquals("/notifications/page", seenUri.get());
        assertEquals("v1", response.getHeader("X-API-Version"));
    }
}
