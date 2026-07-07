package com.galactic.telemetry.pipeline;

import com.galactic.telemetry.model.ReservationConfirmedEvent;
import com.galactic.telemetry.model.RoutePlannedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Verifies the enrichment child spans actually emit with the expected names + tags. */
class EventPipelineObservabilityTest {

    @Test
    void routePipelineEmitsRouteEnrichSpan() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        var config = new EventPipelineConfiguration();

        var unused = config.routePipeline(new SimpleMeterRegistry(), registry)
                .apply(new RoutePlannedEvent(
                        "ROUTE-1", "SP-A", "SP-B", "SCOUT", 10.0, 0.5, Instant.parse("2026-01-01T00:00:00Z")));

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("events.route.enrich")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("processedBy", "telemetry-pipeline");
    }

    @Test
    void reservationPipelineEmitsReservationEnrichSpan() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        var config = new EventPipelineConfiguration();

        var unused = config.reservationPipeline(new SimpleMeterRegistry(), registry)
                .apply(new ReservationConfirmedEvent(
                        1L, "CONFIRMED", "SP-01", "BAY-1", "CUST-01", "SHIP-01", "ROUTE-01",
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                        BigDecimal.TEN, Instant.parse("2026-01-01T00:00:00Z")));

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("events.reservation.enrich")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("processedBy", "telemetry-pipeline");
    }
}
