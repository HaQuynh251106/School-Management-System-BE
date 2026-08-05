package com.sse.app.common;

import org.springframework.http.HttpStatus;

/** Lỗi nghiệp vụ có HTTP status — handler dịch thành {"error": message}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, status.name(), message);
    }

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    public static ApiException notFound(String what)  { return new ApiException(HttpStatus.NOT_FOUND, what + " không tồn tại"); }
    public static ApiException badRequest(String msg)  { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
    public static ApiException conflict(String msg)    { return new ApiException(HttpStatus.CONFLICT, msg); }
    public static ApiException forbidden(String msg)   { return new ApiException(HttpStatus.FORBIDDEN, msg); }
    public static ApiException serviceUnavailable(String msg) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, msg); }
}
