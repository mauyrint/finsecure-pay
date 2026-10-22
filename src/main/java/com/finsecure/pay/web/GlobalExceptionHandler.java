package com.finsecure.pay.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Consistent JSON error envelope; never leaks stack traces to clients. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException ex) {
        log.warn("malformed request body: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return error(400, "MALFORMED_REQUEST", "Request body is missing or is not valid JSON");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException ex) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException ex) {
        return error(405, "METHOD_NOT_ALLOWED", ex.getMethod() + " is not supported for this endpoint");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("unhandled error", ex);
        return error(500, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
