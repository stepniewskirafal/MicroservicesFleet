package com.galactic.starport.api.error;

public class RepositoryUnavailableException extends RuntimeException {
    public RepositoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
