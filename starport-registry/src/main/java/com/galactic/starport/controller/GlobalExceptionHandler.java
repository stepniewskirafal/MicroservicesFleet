package com.galactic.starport.controller;

import com.galactic.starport.service.NoDockingBaysAvailableException;
import com.galactic.starport.service.WrongReservationTimeException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final String ERROR_DETAILS = "details";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> errors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(NoDockingBaysAvailableException.class)
    ResponseEntity<Map<String, String>> handle(NoDockingBaysAvailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(WrongReservationTimeException.class)
    ResponseEntity<Map<String, String>> handle(WrongReservationTimeException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", ERROR_DETAILS, ex.getMessage()));
    }
}
