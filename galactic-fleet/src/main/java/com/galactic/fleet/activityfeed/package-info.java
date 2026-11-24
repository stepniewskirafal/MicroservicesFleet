/**
 * Module {@code activityfeed}: turns a confirmed reservation into the computed records that leave
 * the system on Kafka — {@code telemetry.enriched-reservations} and, when the reservation has a
 * route, {@code telemetry.enriched-routes}.
 *
 * <p>Built as Pipes &amp; Filters (ADR-0022, moved here by ADR-0043): {@code filter} holds the
 * single-purpose steps, {@code model} the records passed between them, {@code pipeline} the
 * composition and the listener that feeds the two chains. A filter returning {@code null} ends its
 * chain — that is how a reservation without a route produces no route record.
 *
 * <p>Exposes nothing — every type lives in a subpackage. It is a pure listener: it may see only the
 * {@code events} named interface of {@code booking}, so it can react to facts but cannot call into
 * another module (ADR-0043). It runs after the publisher's commit, on another thread, in its own
 * transaction (ADR-0043).
 */
@ApplicationModule(
        displayName = "Activity feed",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = "booking::events")
package com.galactic.fleet.activityfeed;

import org.springframework.modulith.ApplicationModule;
