package com.galactic.telemetry.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.telemetry.model.EnrichedReservationEvent;
import com.galactic.telemetry.model.EnrichedRouteEvent;
import com.galactic.telemetry.model.ReservationCreatedEvent;
import com.galactic.telemetry.model.RoutePlannedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Execution(ExecutionMode.CONCURRENT)
class EventPipelineConfigurationTest {

    private SimpleMeterRegistry meterRegistry;
    private EventPipelineConfiguration config;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        config = new EventPipelineConfiguration();
    }

    @Nested
    class ReservationPipeline {

        @Test
        void should_compute_duration_when_both_dates_present() {
            Function<ReservationCreatedEvent, EnrichedReservationEvent> pipeline =
                    config.reservationPipeline(meterRegistry);

            Instant start = Instant.parse("2026-01-01T10:00:00Z");
            Instant end = Instant.parse("2026-01-01T15:30:00Z");
            ReservationCreatedEvent event = aReservationEvent(start, end);

            EnrichedReservationEvent result = pipeline.apply(event);

            assertThat(result.durationHours()).isEqualTo(5);
        }

        @Test
        void should_default_duration_to_zero_when_start_is_null() {
            Function<ReservationCreatedEvent, EnrichedReservationEvent> pipeline =
                    config.reservationPipeline(meterRegistry);

            ReservationCreatedEvent event = aReservationEvent(null, Instant.now());

            EnrichedReservationEvent result = pipeline.apply(event);

            assertThat(result.durationHours()).isZero();
        }

        @Test
        void should_default_duration_to_zero_when_end_is_null() {
            Function<ReservationCreatedEvent, EnrichedReservationEvent> pipeline =
                    config.reservationPipeline(meterRegistry);

            ReservationCreatedEvent event = aReservationEvent(Instant.now(), null);

            EnrichedReservationEvent result = pipeline.apply(event);

            assertThat(result.durationHours()).isZero();
        }

        @Test
        void should_set_event_type_to_reservation_confirmed() {
            Function<ReservationCreatedEvent, EnrichedReservationEvent> pipeline =
                    config.reservationPipeline(meterRegistry);

            EnrichedReservationEvent result = pipeline.apply(aReservationEvent(Instant.now(), Instant.now()));

            assertThat(result.eventType()).isEqualTo("RESERVATION_CONFIRMED");
        }

        @Test
        void should_set_processed_by_to_telemetry_pipeline() {
            Function<ReservationCreatedEvent, EnrichedReservationEvent> pipeline =
                    config.reservationPipeline(meterRegistry);

            EnrichedReservationEvent result = pipeline.apply(aReservationEvent(Instant.now(), Instant.now()));

            assertThat(result.processedBy()).isEqualTo("telemetry-pipeline");
        }

        @Test
        void should_preserve_all_original_fields() {
            Function<ReservationCreatedEvent, EnrichedReservationEvent> pipeline =
                    config.reservationPipeline(meterRegistry);

            ReservationCreatedEvent event = new ReservationCreatedEvent(
                    42L,
                    "CONFIRMED",
                    "SP-01",
                    "BAY-A",
                    "CUST-01",
                    "SHIP-01",
                    "ROUTE-01",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-02T00:00:00Z"),
                    new BigDecimal("100.00"));

            EnrichedReservationEvent result = pipeline.apply(event);

            assertThat(result.reservationId()).isEqualTo(42L);
            assertThat(result.status()).isEqualTo("CONFIRMED");
            assertThat(result.starportCode()).isEqualTo("SP-01");
            assertThat(result.dockingBayLabel()).isEqualTo("BAY-A");
            assertThat(result.customerCode()).isEqualTo("CUST-01");
            assertThat(result.shipCode()).isEqualTo("SHIP-01");
            assertThat(result.routeCode()).isEqualTo("ROUTE-01");
            assertThat(result.feeCharged()).isEqualByComparingTo("100.00");
        }

        @Test
        void should_increment_received_and_enriched_counters() {
            Function<ReservationCreatedEvent, EnrichedReservationEvent> pipeline =
                    config.reservationPipeline(meterRegistry);

            var unused1 = pipeline.apply(aReservationEvent(Instant.now(), Instant.now()));
            var unused2 = pipeline.apply(aReservationEvent(Instant.now(), Instant.now()));

            Counter received = meterRegistry.get("events.reservation.received").counter();
            Counter enriched = meterRegistry.get("events.reservation.enriched").counter();
            assertThat(received.count()).isEqualTo(2.0);
            assertThat(enriched.count()).isEqualTo(2.0);
        }
    }

    @Nested
    class RoutePipeline {

        @Test
        void should_set_event_type_to_route_planned() {
            Function<RoutePlannedEvent, EnrichedRouteEvent> pipeline = config.routePipeline(meterRegistry);

            EnrichedRouteEvent result = pipeline.apply(aRouteEvent(0.5));

            assertThat(result.eventType()).isEqualTo("ROUTE_PLANNED");
        }

        @Test
        void should_preserve_all_original_fields() {
            Function<RoutePlannedEvent, EnrichedRouteEvent> pipeline = config.routePipeline(meterRegistry);

            RoutePlannedEvent event = new RoutePlannedEvent(
                    "ROUTE-X", "SP-A", "SP-B", "CRUISER", 15.0, 0.42, Instant.parse("2026-01-01T00:00:00Z"));

            EnrichedRouteEvent result = pipeline.apply(event);

            assertThat(result.routeId()).isEqualTo("ROUTE-X");
            assertThat(result.originPortId()).isEqualTo("SP-A");
            assertThat(result.destinationPortId()).isEqualTo("SP-B");
            assertThat(result.shipClass()).isEqualTo("CRUISER");
            assertThat(result.etaHours()).isEqualTo(15.0);
            assertThat(result.riskScore()).isEqualTo(0.42);
        }

        @Test
        void should_increment_received_and_enriched_counters() {
            Function<RoutePlannedEvent, EnrichedRouteEvent> pipeline = config.routePipeline(meterRegistry);

            var unused1 = pipeline.apply(aRouteEvent(0.1));
            var unused2 = pipeline.apply(aRouteEvent(0.5));
            var unused3 = pipeline.apply(aRouteEvent(0.9));

            Counter received = meterRegistry.get("events.route.received").counter();
            Counter enriched = meterRegistry.get("events.route.enriched").counter();
            assertThat(received.count()).isEqualTo(3.0);
            assertThat(enriched.count()).isEqualTo(3.0);
        }
    }

    @Nested
    class RiskClassification {

        @ParameterizedTest(name = "riskScore={0} → {1}")
        @CsvSource({
            "0.0,   LOW",
            "0.1,   LOW",
            "0.29,  LOW",
            "0.3,   MEDIUM",
            "0.5,   MEDIUM",
            "0.69,  MEDIUM",
            "0.7,   HIGH",
            "0.85,  HIGH",
            "0.99,  HIGH",
        })
        void should_classify_risk_correctly(double riskScore, String expectedLevel) {
            Function<RoutePlannedEvent, EnrichedRouteEvent> pipeline = config.routePipeline(meterRegistry);

            EnrichedRouteEvent result = pipeline.apply(aRouteEvent(riskScore));

            assertThat(result.riskLevel()).isEqualTo(expectedLevel);
        }
    }

    private static ReservationCreatedEvent aReservationEvent(Instant start, Instant end) {
        return new ReservationCreatedEvent(
                1L, "CONFIRMED", "SP-01", "BAY-1", "CUST-01", "SHIP-01", "ROUTE-01", start, end, BigDecimal.TEN);
    }

    private static RoutePlannedEvent aRouteEvent(double riskScore) {
        return new RoutePlannedEvent(
                "ROUTE-01", "SP-A", "SP-B", "SCOUT", 10.0, riskScore, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
