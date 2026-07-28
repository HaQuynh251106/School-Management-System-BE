package com.sse.app.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

/**
 * Public, version-stable error contract shared by all website APIs.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String error,
        String path,
        String requestId,
        Map<String, String> fieldErrors
) {
    public static ApiErrorResponse of(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                RequestCorrelationFilter.currentId(request),
                fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors)
        );
    }
}
