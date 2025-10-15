package com.galactic.starport.domain.exception;

public class RepositoryUnavailableException extends RuntimeException {
    public RepositoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
