package com.example.transactions.controller;

import com.example.transactions.service.IdempotencyConflictException;
import com.example.transactions.service.TransactionProcessingService;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<?> conflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("timestamp", Instant.now(), "status", 409, "error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", Instant.now(), "status", 400, "error", "Request validation failed"));
    }

    @ExceptionHandler(TransactionProcessingService.PermanentTransactionException.class)
    public ResponseEntity<?> business(RuntimeException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "timestamp", Instant.now(), "status", 422, "error", ex.getMessage()));
    }
}
