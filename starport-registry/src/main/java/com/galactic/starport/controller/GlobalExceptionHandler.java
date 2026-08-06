package com.galactic.starport.controller;

import com.galactic.starport.service.CustomerNotFoundException;
import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.NoDockingBaysAvailableException;
import com.galactic.starport.service.ShipNotFoundException;
import com.galactic.starport.service.StarportNotFoundException;
import com.galactic.starport.service.routeplanner.PlannerContractException;
import com.galactic.starport.service.routeplanner.RoutePlannerUnavailableException;
import com.galactic.starport.service.routeplanner.RouteUnavailableException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_DETAILS = "details";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> errors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.unprocessableEntity().body(errors); // 422
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, String>> handleHandlerValidation(HandlerMethodValidationException ex) {
        // Spring 6.1+: because the handler method also has a constrained simple parameter
        // (@PathVariable @NotBlank code), @Valid @RequestBody body violations surface here instead of
        // as MethodArgumentNotValidException. Map them to field→message just like the body case, so a
        // missing field is a 422 (not the catch-all 500).
        Map<String, String> errors = new HashMap<>();
        ex.getAllValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors bodyErrors) {
                bodyErrors.getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors()
                        .forEach(err -> errors.put(name != null ? name : "request", err.getDefaultMessage()));
            }
        });
        return ResponseEntity.unprocessableEntity().body(errors); // 422
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Malformed JSON", ERROR_DETAILS, "Request body could not be parsed"));
    }

    @ExceptionHandler(NoDockingBaysAvailableException.class)
    ResponseEntity<Map<String, String>> handle(NoDockingBaysAvailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(InvalidReservationTimeException.class)
    ResponseEntity<Map<String, String>> handle(InvalidReservationTimeException ex) {
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

    // Infrastructure failure (planner 5xx/timeout/circuit open): the request was FINE — "come
    // back shortly". 503 + Retry-After lets well-behaved clients schedule a retry; a 409 here
    // would tell them to change a request that has nothing wrong with it. Retry-After matches
    // wait-duration-in-open-state (10s), the earliest moment the circuit may close again.
    @ExceptionHandler(RoutePlannerUnavailableException.class)
    ResponseEntity<Map<String, String>> handle(RoutePlannerUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .body(Map.of("error", "ROUTE_PLANNER_UNAVAILABLE", ERROR_DETAILS, ex.getMessage()));
    }

    // The planner answered a 4xx outside its contract (400 from our serializer, 404 from a wrong
    // URL, 422 rejecting our DTO): neither the customer's fault (no 409) nor a planner outage (no
    // 503 + Retry-After). 502 = "the upstream gave an invalid response" — which is what happened,
    // and a Retry-After would be a lie: coming back later cannot fix our code. ADR-0040.
    @ExceptionHandler(PlannerContractException.class)
    ResponseEntity<Map<String, String>> handle(PlannerContractException ex) {
        log.error("Route planner contract violation", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error",
                        "ROUTE_PLANNER_CONTRACT",
                        ERROR_DETAILS,
                        "The route planner answered outside its contract (HTTP " + ex.getStatusCode() + ")"));
    }

    // Domain rejection (RoutePlanningRejectedException) and any legacy RouteUnavailableException:
    // the planner answered "no" to THIS request — retrying it verbatim cannot help. 409.
    @ExceptionHandler(RouteUnavailableException.class)
    ResponseEntity<Map<String, String>> handle(RouteUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "ROUTE_UNAVAILABLE", ERROR_DETAILS, ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
    }
}
