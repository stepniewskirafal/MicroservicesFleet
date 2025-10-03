package com.galactic.starport.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.events.topics")
public record TopicsProperties(
        String reservations, // np. reservationCreated-out-0
        String tariffs, // np. tariffCalculated-out-0
        String incidents // np. incidentRecorded-out-0
        ) {}
