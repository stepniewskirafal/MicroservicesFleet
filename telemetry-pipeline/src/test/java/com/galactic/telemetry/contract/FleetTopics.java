package com.galactic.telemetry.contract;

/**
 * Canonical Kafka topic names for the fleet — the single source of truth the cross-service contract
 * test ({@link TopicDestinationContractTest}) asserts every producer and consumer binding against.
 *
 * <p>These are the wire contract (topic names), distinct from per-service Spring Cloud Stream binding
 * names. Kept in the consumer (telemetry) test scope deliberately: a standalone shared Maven module
 * would force every service Dockerfile to COPY its pom into the reactor — disproportionate blast
 * radius for constants only a test references (destinations live in YAML, not Java).
 */
final class FleetTopics {

    private FleetTopics() {}

    // Cross-service channels (producer in one service, consumer in another)
    static final String STARPORT_RESERVATIONS = "starport.reservations";
    static final String STARPORT_ROUTE_PLANNED = "starport.route-planned";

    // Telemetry outputs (enriched re-publish) — documented for completeness
    static final String TELEMETRY_ENRICHED_RESERVATIONS = "telemetry.enriched-reservations";
    static final String TELEMETRY_ENRICHED_ROUTES = "telemetry.enriched-routes";
    static final String TELEMETRY_RAW = "telemetry.raw";
    static final String TELEMETRY_ALERTS = "telemetry.alerts";
}
