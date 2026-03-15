package com.galactic.traderoute.adapter.out.kafka;

public class EventPublishingException extends RuntimeException {
    public EventPublishingException(String message) {
        super(message);
    }
}
