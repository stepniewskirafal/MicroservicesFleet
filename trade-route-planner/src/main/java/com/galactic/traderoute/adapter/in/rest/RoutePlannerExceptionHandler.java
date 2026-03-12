package com.galactic.traderoute.adapter.in.rest;

import com.galactic.traderoute.domain.model.RouteRejectionException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RoutePlannerExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(RoutePlannerExceptionHandler.class);

    @ExceptionHandler(RouteRejectionException.class)
    ResponseEntity<RouteRejectedResponse> handleRouteRejection(RouteRejectionException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new RouteRejectedResponse("ROUTE_REJECTED", ex.getReason(), ex.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> errors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.unprocessableEntity().body(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Malformed JSON", "details", "Request body could not be parsed"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
    }
}
