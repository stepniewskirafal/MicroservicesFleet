package com.galactic.traderoute.domain.model;

public class RouteRejectionException extends RuntimeException {

    private final String reason;
    private final String details;

    public RouteRejectionException(String reason, String details) {
        super("Route rejected: " + reason + " — " + details);
        this.reason = reason;
        this.details = details;
    }

    public String getReason() {
        return reason;
    }

    public String getDetails() {
        return details;
    }
}
