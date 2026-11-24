package com.galactic.fleet.activityfeed.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.galactic.fleet.activityfeed.model.EnrichedReservationEvent;
import com.galactic.fleet.activityfeed.model.EnrichedRouteEvent;
import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * The listener that turns a confirmed reservation into the two computed records leaving on Kafka
 * (ADR-0043). The broker is a mocked {@link StreamBridge}; the enrichment functions are
 * the real ones.
 */
class FleetEventListenerTest {

    private static final Instant START = Instant.parse("2031-03-01T20:00:00Z");
    private static final ReservationConfirmed.Route ROUTE =
            new ReservationConfirmed.Route("ROUTE-ABCD1234", "TATOO", "HOTH", "FREIGHTER", 21.5, 0.35, START);
    private static final String REQUEST_TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACEPARENT = "00-" + REQUEST_TRACE + "-b7ad6b7169203331-01";

    private StreamBridge streamBridge;
    private SimpleMeterRegistry meterRegistry;
    private Tracer tracer;
    private Propagator propagator;
    private FleetEventListener listener;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        meterRegistry = new SimpleMeterRegistry();
        tracer = mock(Tracer.class);
        propagator = mock(Propagator.class);
        EventPipelineConfiguration pipelines = new EventPipelineConfiguration();
        listener = new FleetEventListener(
                pipelines.reservationPipeline(meterRegistry, ObservationRegistry.NOOP),
                pipelines.routePipeline(meterRegistry, ObservationRegistry.NOOP),
                streamBridge,
                meterRegistry,
                tracer,
                propagator);
        given(streamBridge.send(any(String.class), any())).willReturn(true);
    }

    @Test
    void confirmed_reservation_with_a_route_produces_both_records() {
        listener.on(aReservation(ROUTE, null));

        Message<?> reservation = sentTo(FleetEventListener.ENRICHED_RESERVATIONS_BINDING);
        assertThat(reservation.getPayload()).isInstanceOfSatisfying(EnrichedReservationEvent.class, enriched -> {
            assertThat(enriched.reservationId()).isEqualTo(7L);
            assertThat(enriched.routeCode()).isEqualTo("ROUTE-ABCD1234");
            assertThat(enriched.durationHours()).isEqualTo(3);
            assertThat(enriched.processedBy()).isEqualTo("telemetry-pipeline");
        });

        Message<?> route = sentTo(FleetEventListener.ENRICHED_ROUTES_BINDING);
        assertThat(route.getPayload()).isInstanceOfSatisfying(EnrichedRouteEvent.class, enriched -> {
            assertThat(enriched.routeId()).isEqualTo("ROUTE-ABCD1234");
            assertThat(enriched.originPortId()).isEqualTo("TATOO");
            assertThat(enriched.destinationPortId()).isEqualTo("HOTH");
            assertThat(enriched.shipClass()).isEqualTo("FREIGHTER");
            assertThat(enriched.riskLevel()).isEqualTo("MEDIUM");
            assertThat(enriched.plannedAt()).isEqualTo(START);
        });
    }

    @Test
    void every_record_carries_a_stable_key_a_consumer_can_deduplicate_on() {
        // Delivery is at-least-once: the same record can arrive twice, and the key is how a consumer
        // tells (ADR-0043).
        listener.on(aReservation(ROUTE, null));

        assertThat(sentTo(FleetEventListener.ENRICHED_RESERVATIONS_BINDING)
                        .getHeaders()
                        .get(KafkaHeaders.KEY))
                .isEqualTo("7");
        assertThat(sentTo(FleetEventListener.ENRICHED_ROUTES_BINDING)
                        .getHeaders()
                        .get(KafkaHeaders.KEY))
                .isEqualTo("ROUTE-ABCD1234");
    }

    @Test
    void confirmed_reservation_without_a_route_produces_only_the_reservation_record() {
        listener.on(aReservation(null, null));

        verify(streamBridge).send(eq(FleetEventListener.ENRICHED_RESERVATIONS_BINDING), any());
        verify(streamBridge, never()).send(eq(FleetEventListener.ENRICHED_ROUTES_BINDING), any());
    }

    @Test
    void a_failing_broker_propagates_so_the_publication_stays_incomplete() {
        // The Event Publication Registry completes a publication only when the listener returns
        // normally. Swallowing the failure here would lose the event for good.
        IllegalStateException brokerDown = new IllegalStateException("broker down");
        given(streamBridge.send(eq(FleetEventListener.ENRICHED_RESERVATIONS_BINDING), any()))
                .willThrow(brokerDown);

        assertThatThrownBy(() -> listener.on(aReservation(ROUTE, null))).isSameAs(brokerDown);

        assertThat(errorCount("reservation")).isEqualTo(1.0);
        verify(streamBridge, never()).send(eq(FleetEventListener.ENRICHED_ROUTES_BINDING), any());
    }

    @Test
    void a_refused_send_is_a_failure_too() {
        given(streamBridge.send(eq(FleetEventListener.ENRICHED_ROUTES_BINDING), any()))
                .willReturn(false);

        assertThatThrownBy(() -> listener.on(aReservation(ROUTE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FleetEventListener.ENRICHED_ROUTES_BINDING);

        assertThat(errorCount("route")).isEqualTo(1.0);
    }

    @Test
    void first_delivery_stays_in_the_trace_the_thread_already_carries() {
        // Normal path: the task decorator brought the request's trace onto this thread.
        currentTraceIs(REQUEST_TRACE);

        listener.on(aReservation(null, TRACEPARENT));

        verifyNoInteractions(propagator);
        verify(streamBridge).send(eq(FleetEventListener.ENRICHED_RESERVATIONS_BINDING), any());
    }

    @Test
    void redelivery_rejoins_the_trace_of_the_request_that_confirmed_the_reservation() {
        // Re-delivery: the thread carries the maintenance job's trace, not the request's.
        currentTraceIs("ffffffffffffffffffffffffffffffff");
        Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
        Span redelivery = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        given(propagator.extract(eq(Map.of("traceparent", TRACEPARENT)), any())).willReturn(builder);
        given(builder.start()).willReturn(redelivery);
        given(tracer.withSpan(redelivery)).willReturn(scope);

        listener.on(aReservation(null, TRACEPARENT));

        InOrder order = inOrder(tracer, streamBridge, scope, redelivery);
        order.verify(tracer).withSpan(redelivery);
        order.verify(streamBridge).send(eq(FleetEventListener.ENRICHED_RESERVATIONS_BINDING), any());
        order.verify(scope).close();
        order.verify(redelivery).end();
    }

    @Test
    void redelivery_outside_any_trace_rejoins_the_request_trace_as_well() {
        given(tracer.currentSpan()).willReturn(null);
        Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
        Span redelivery = mock(Span.class);
        given(propagator.extract(any(), any())).willReturn(builder);
        given(builder.start()).willReturn(redelivery);
        given(tracer.withSpan(redelivery)).willReturn(mock(Tracer.SpanInScope.class));

        listener.on(aReservation(null, TRACEPARENT));

        verify(tracer).withSpan(redelivery);
        verify(redelivery).end();
    }

    @Test
    void an_event_without_trace_context_is_published_as_is() {
        listener.on(aReservation(null, null));

        verifyNoInteractions(propagator);
        verify(streamBridge).send(eq(FleetEventListener.ENRICHED_RESERVATIONS_BINDING), any());
    }

    @Test
    void the_listener_runs_after_the_commit_on_another_thread() throws Exception {
        // @ApplicationModuleListener = @Async + @Transactional(REQUIRES_NEW) + after-commit delivery.
        // Replacing it with a plain @EventListener would put this code back on the request thread,
        // inside the reservation's transaction (ADR-0043).
        assertThat(FleetEventListener.class
                        .getMethod("on", ReservationConfirmed.class)
                        .isAnnotationPresent(ApplicationModuleListener.class))
                .isTrue();
    }

    private void currentTraceIs(String traceId) {
        Span current = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        given(tracer.currentSpan()).willReturn(current);
        given(current.context()).willReturn(context);
        given(context.traceId()).willReturn(traceId);
    }

    private Message<?> sentTo(String binding) {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(streamBridge).send(eq(binding), sent.capture());
        assertThat(sent.getValue()).isInstanceOf(Message.class);
        return (Message<?>) sent.getValue();
    }

    private double errorCount(String eventKind) {
        return meterRegistry
                .get("events.enrichment.errors")
                .tag("event", eventKind)
                .counter()
                .count();
    }

    private static ReservationConfirmed aReservation(ReservationConfirmed.Route route, String traceparent) {
        return new ReservationConfirmed(
                7L,
                "CONFIRMED",
                "HOTH",
                "B-07",
                "CUST-002",
                "FALCON-002",
                START,
                START.plusSeconds(3 * 3600),
                new BigDecimal("360.00"),
                START,
                route,
                traceparent);
    }
}
