package com.galactic.starport.api.error;

public class NoDockingBaysAvailableException extends RuntimeException {
    public NoDockingBaysAvailableException(String msg) {
        super(msg);
    }
}
