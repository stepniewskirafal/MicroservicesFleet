/**
 * Module {@code activityfeed}: turns a confirmed reservation into the computed records that leave
 * the system on Kafka — {@code telemetry.enriched-reservations} and, when the reservation has a
 * route, {@code telemetry.enriched-routes}.
 *
 * <p>Exposes nothing — every type lives in {@code internal}. It is a pure listener: it may see only
 * the {@code events} named interface of {@code booking}, so it can react to facts but cannot call
 * into another module (ADR-0044). It runs after the publisher's commit, on another thread, in its own
 * transaction (ADR-0046).
 */
@ApplicationModule(
        displayName = "Activity feed",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = "booking::events")
package com.galactic.fleet.activityfeed;

import org.springframework.modulith.ApplicationModule;
