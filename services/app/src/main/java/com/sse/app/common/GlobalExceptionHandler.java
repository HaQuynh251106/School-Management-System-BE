package com.sse.app.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mọi lỗi trả về cùng một contract và requestId để hỗ trợ người dùng an toàn. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        return response(ex.getStatus(), ex.getCode(), ex.getMessage(), request, Map.of());
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Nội dung yêu cầu không đúng định dạng JSON.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request
    ) {
        String detail = ex.getMostSpecificCause() == null
                ? "" : String.valueOf(ex.getMostSpecificCause().getMessage());
        if (detail.contains("uq_classes_year_homeroom_teacher")) {
            return response(
                    HttpStatus.CONFLICT,
                    "HOMEROOM_TEACHER_ALREADY_ASSIGNED",
                    "Giáo viên đã chủ nhiệm một lớp khác trong cùng năm học.",
                    request,
                    Map.of()
            );
        }
        return response(
                HttpStatus.CONFLICT,
                "DATA_CONFLICT",
                "Dữ liệu bị trùng hoặc đang được sử dụng. Vui lòng kiểm tra lại.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NoResourceFoundException ex,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Đường dẫn không tồn tại", request, Map.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MISSING_PARAMETER",
                "Thiếu tham số bắt buộc: " + ex.getParameterName(),
                request,
                Map.of(ex.getParameterName(), "Trường này là bắt buộc")
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleRequestParameterTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Tham số không đúng định dạng: " + ex.getName(),
                request,
                Map.of(ex.getName(), "Giá trị không hợp lệ")
        );
    }

    /** Client closed the connection (commonly a health probe during restart). */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("Client disconnected before response completed path={}", request.getRequestURI());
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
