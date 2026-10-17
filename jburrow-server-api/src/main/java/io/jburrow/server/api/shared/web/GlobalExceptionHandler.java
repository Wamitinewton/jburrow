package io.jburrow.server.api.shared.web;

import io.jburrow.server.api.shared.error.ErrorCode;
import io.jburrow.server.api.shared.error.JBurrowException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every failure as an {@link ErrorResponse}. A new error is a new {@link ErrorCode} constant thrown as a
 * {@link JBurrowException}, never a new handler here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String VALIDATION_MESSAGE = "The request is invalid.";
    static final String NOT_FOUND_MESSAGE = "No resource exists at this path.";
    static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";

    @ExceptionHandler(JBurrowException.class)
    ResponseEntity<ErrorResponse> handleJBurrow(JBurrowException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("{} on {} {}", code.code(), request.getMethod(), request.getRequestURI(), ex);
        } else {
            log.debug("{} on {} {}: {}", code.code(), request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
        return render(code, ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return render(ErrorCode.VALIDATION_FAILED, VALIDATION_MESSAGE, fieldErrors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> handleInvalidParameters(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldError(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return render(ErrorCode.VALIDATION_FAILED, VALIDATION_MESSAGE, fieldErrors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return render(ErrorCode.VALIDATION_FAILED, VALIDATION_MESSAGE, fieldErrors, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return render(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return render(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, List.of(), request);
    }

    private ResponseEntity<ErrorResponse> render(
            ErrorCode code, String message, List<FieldError> fieldErrors, HttpServletRequest request) {
        ErrorResponse body =
                new ErrorResponse(false, code.code(), message, fieldErrors, Instant.now(), request.getRequestURI());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }
}
