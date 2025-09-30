package com.galactic.starport.api.error;

import com.galactic.starport.domain.exception.DockingBayNotFoundException;
import com.galactic.starport.domain.exception.NoDockingBaysAvailableException;
import com.galactic.starport.domain.exception.RepositoryUnavailableException;
import com.galactic.starport.domain.exception.StarportNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StarportNotFoundException.class)
    ProblemDetail handle(StarportNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Starport not found");
        pd.setType(URI.create("https://docs.starport/errors/starport-not-found"));
        pd.setProperty("starportCode", ex.starportCode());
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(DockingBayNotFoundException.class)
    ProblemDetail handle(DockingBayNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Docking bay not found");
        pd.setType(URI.create("https://docs.starport/errors/docking-bay-not-found"));
        pd.setProperty("bayId", ex.bayId().toString());
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(NoDockingBaysAvailableException.class)
    ProblemDetail handle(NoDockingBaysAvailableException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("No docking bays available");
        pd.setType(URI.create("https://docs.starport/errors/no-bays"));
        pd.setProperty("starportCode", ex.starportCode());
        pd.setProperty("from", ex.from().toString());
        pd.setProperty("to", ex.to().toString());
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(RepositoryUnavailableException.class)
    ProblemDetail handle(RepositoryUnavailableException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setTitle("Repository unavailable");
        pd.setType(URI.create("https://docs.starport/errors/repository-unavailable"));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setTitle("Internal server error");
        pd.setType(URI.create("https://docs.starport/errors/internal"));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
