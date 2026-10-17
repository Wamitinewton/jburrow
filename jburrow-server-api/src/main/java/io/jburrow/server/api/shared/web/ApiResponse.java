package io.jburrow.server.api.shared.web;

/**
 * Success envelope for every endpoint. Failures are rendered as {@link ErrorResponse} by
 * {@link GlobalExceptionHandler}, never built here.
 */
public record ApiResponse<T>(boolean success, String message, T data) {

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null);
    }
}
