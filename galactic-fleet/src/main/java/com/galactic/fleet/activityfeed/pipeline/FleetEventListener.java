package com.galactic.fleet.activityfeed.pipeline;

import com.galactic.fleet.activityfeed.model.EnrichedReservationEvent;
import com.galactic.fleet.activityfeed.model.EnrichedRouteEvent;
import com.galactic.fleet.booking.events.ReservationConfirmed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link ReservationConfirmed} and puts the computed records on their Kafka topics
 * ({@code telemetry.enriched-reservations}, and {@code telemetry.enriched-routes} when the
 * reservation has a route).
 *
 * <p><b>Decoupled from the publisher in time, thread and transaction</b> (ADR-0043).
 * {@link ApplicationModuleListener} means: after the publisher's commit, on another thread, in a
 * transaction of its own. The HTTP response of the reservation does not wait for this code and
 * cannot be failed by it.
 *
 * <p><b>Failures propagate.</b> The Event Publication Registry marks a publication complete only
 * when this method returns normally. Throwing leaves the row in {@code event_publication}, and
 * {@code EventPublicationMaintenance} re-delivers it — which is why a failure is counted and
 * rethrown, never swallowed.
 *
 * <p><b>At-least-once, with a key to deduplicate on</b> (ADR-0043). A re-delivery after a partial
 * success sends the reservation record a second time. Every record therefore carries a stable Kafka
 * key — the reservation id, or the route code — so a consumer can recognise the duplicate.
 */
@Component
class FleetEventListener {

    static final String ENRICHED_RESERVATIONS_BINDING = "enrichedReservations-out-0";
    static final String ENRICHED_ROUTES_BINDING = "enrichedRoutes-out-0";

    private static final Logger log = LoggerFactory.getLogger(FleetEventListener.class);
    private static final String METRIC_ERRORS = "events.enrichment.errors";
    private static final String TRACEPARENT = "traceparent";
    private static final String REDELIVERY_SPAN = "events.reservation.redelivery";

    private final Function<ReservationConfirmed, EnrichedReservationEvent> reservationPipeline;
    private final Function<ReservationConfirmed, EnrichedRouteEvent> routePipeline;
    private final StreamBridge streamBridge;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    FleetEventListener(
            @Qualifier("reservationPipeline")
                    Function<ReservationConfirmed, EnrichedReservationEvent> reservationPipeline,
            @Qualifier("routePipeline") Function<ReservationConfirmed, EnrichedRouteEvent> routePipeline,
            StreamBridge streamBridge,
            MeterRegistry meterRegistry,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator) {
        this(
                reservationPipeline,
                routePipeline,
                streamBridge,
                meterRegistry,
                tracer.getIfAvailable(() -> Tracer.NOOP),
                propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    FleetEventListener(
            Function<ReservationConfirmed, EnrichedReservationEvent> reservationPipeline,
            Function<ReservationConfirmed, EnrichedRouteEvent> routePipeline,
            StreamBridge streamBridge,
            MeterRegistry meterRegistry,
            Tracer tracer,
            Propagator propagator) {
        this.reservationPipeline = reservationPipeline;
        this.routePipeline = routePipeline;
        this.streamBridge = streamBridge;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @ApplicationModuleListener
    public void on(ReservationConfirmed event) {
        if (!belongsToAnotherTrace(event)) {
            publishRecords(event);
            return;
        }
        // Re-delivery: this thread carries the trace of whatever re-submitted the event (the
        // maintenance job), not of the request that confirmed the reservation. Rejoin the original
        // trace from the context stored in the event, so the records land in the right trace.
        Span span = propagator
                .extract(Map.of(TRACEPARENT, event.traceparent()), Map::get)
                .name(REDELIVERY_SPAN)
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            publishRecords(event);
        } catch (RuntimeException ex) {
            span.error(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /** True when the event names a trace and the current thread is not already inside it. */
    private boolean belongsToAnotherTrace(ReservationConfirmed event) {
        String eventTraceId = traceIdOf(event.traceparent());
        if (eventTraceId == null) {
            return false;
        }
        Span current = tracer.currentSpan();
        return current == null || !eventTraceId.equals(current.context().traceId());
    }

    /** W3C traceparent: {@code 00-<32 hex trace id>-<16 hex span id>-<flags>}. */
    private static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return parts.length == 4 ? parts[1] : null;
    }

    private void publishRecords(ReservationConfirmed event) {
        try {
            send(
                    ENRICHED_RESERVATIONS_BINDING,
                    String.valueOf(event.reservationId()),
                    reservationPipeline.apply(event));
        } catch (RuntimeException ex) {
            throw failed("reservation", event, ex);
        }
        try {
            // null: the route chain ended in its first filter — the reservation has no route.
            EnrichedRouteEvent route = routePipeline.apply(event);
            if (route != null) {
                send(ENRICHED_ROUTES_BINDING, route.routeId(), route);
            }
        } catch (RuntimeException ex) {
            throw failed("route", event, ex);
        }
    }

    private void send(String binding, String key, Object enriched) {
        var message = MessageBuilder.withPayload(enriched)
                .setHeader(KafkaHeaders.KEY, key)
                .build();
        if (!streamBridge.send(binding, message)) {
            throw new IllegalStateException("StreamBridge.send returned false for binding " + binding);
        }
    }

    private RuntimeException failed(String eventKind, ReservationConfirmed event, RuntimeException ex) {
        log.error(
                "Publishing the enriched {} record failed, the publication stays incomplete: reservationId={}",
                eventKind,
                event.reservationId(),
                ex);
        Counter.builder(METRIC_ERRORS)
                .description("Confirmed reservations whose enriched record could not be computed or published")
                .tag("event", eventKind)
                .register(meterRegistry)
                .increment();
        return ex;
    }
}
