/**
 * Module {@code shipmonitoring}: sensor readings in, anomaly alerts out — a Pipes &amp; Filters
 * chain over {@code telemetry.raw} producing {@code telemetry.alerts} (ADR-0022).
 *
 * <p>Fully self-contained (ADR-0046): it exposes nothing (every type lives in a subpackage),
 * depends on no other module, publishes no application events and listens to none. Its only
 * contacts with the outside are the two Kafka topics.
 */
@ApplicationModule(
        displayName = "Ship monitoring",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {})
package com.galactic.fleet.shipmonitoring;

import org.springframework.modulith.ApplicationModule;
