package com.galactic.traderoute.adapter.in.rest;

import com.galactic.traderoute.domain.model.RouteRejectionException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RoutePlannerExceptionHandler {

    @ExceptionHandler(RouteRejectionException.class)
    ResponseEntity<RouteRejectedResponse> handleRouteRejection(RouteRejectionException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new RouteRejectedResponse("ROUTE_REJECTED", ex.getReason(), ex.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.unprocessableEntity().body(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Malformed JSON", "details", ex.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "details", ex.getMessage()));
    }
}
