package com.galactic.starport.controller;

import com.galactic.starport.service.*;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final String ERROR_DETAILS = "details";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> errors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.unprocessableEntity().body(errors); // 422
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleNotReadable(HttpMessageNotReadableException ex) {
        ex.getMostSpecificCause();
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "error",
                        "Malformed JSON",
                        ERROR_DETAILS,
                        ex.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(NoDockingBaysAvailableException.class)
    ResponseEntity<Map<String, String>> handle(NoDockingBaysAvailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(WrongReservationTimeException.class)
    ResponseEntity<Map<String, String>> handle(WrongReservationTimeException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(StarportNotFoundException.class)
    ResponseEntity<Map<String, String>> handle(StarportNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<Map<String, String>> handle(CustomerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(ShipNotFoundException.class)
    ResponseEntity<Map<String, String>> handle(ShipNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", ERROR_DETAILS, ex.getMessage()));
    }
}
