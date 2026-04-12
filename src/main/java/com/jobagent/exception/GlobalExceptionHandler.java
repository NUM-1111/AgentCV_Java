package com.jobagent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(int code, String message) {}

    @ExceptionHandler(ContextWindowExceededException.class)
    public ResponseEntity<ApiError> handleContextWindowExceeded(ContextWindowExceededException ex) {
        log.warn("Input too long: estimatedTokens={}, maxTokens={}", ex.getEstimatedTokens(), ex.getMaxTokens());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(400, ex.getMessage()));
    }

    @ExceptionHandler(AiOutputValidationException.class)
    public ResponseEntity<ApiError> handleAiOutputValidationException(AiOutputValidationException ex) {
        log.error("AI output validation failed", ex);
        String message = "AI 分析引擎暂时开小差了，请稍后重试。详情: " + ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(500, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        String detail = ex.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = ex.getClass().getSimpleName();
        }
        String message = "AI 分析引擎暂时开小差了，请稍后重试。详情: " + detail;
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(500, message));
    }
}
