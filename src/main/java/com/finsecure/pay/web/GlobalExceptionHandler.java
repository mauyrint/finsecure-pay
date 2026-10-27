package com.finsecure.pay.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException ex) {
        Map<String, Object> body = Map.of(
                "status", 400,
                "code", "MALFORMED_REQUEST",
                "message", "Request body is missing or is not valid JSON");
        return ResponseEntity.badRequest().body(body);
    }
}
