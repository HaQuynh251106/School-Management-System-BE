package com.sse.app.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mọi lỗi trả về cùng một contract và requestId để hỗ trợ người dùng an toàn. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        return response(ex.getStatus(), ex.getStatus().name(), ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(field ->
                fields.putIfAbsent(field.getField(), String.valueOf(field.getDefaultMessage())));
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Dữ liệu chưa hợp lệ. Vui lòng kiểm tra các trường được đánh dấu.",
                request,
                fields
        );
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleIllegal(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NoResourceFoundException ex,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Đường dẫn không tồn tại", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleOther(Exception ex, HttpServletRequest request) {
        String requestId = RequestCorrelationFilter.currentId(request);
        log.error("Unhandled API error requestId={} path={}", requestId, request.getRequestURI(), ex);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Hệ thống đang gặp sự cố. Vui lòng thử lại hoặc gửi mã hỗ trợ cho quản trị viên.",
                request,
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status, code, message, request, fieldErrors));
    }
}
