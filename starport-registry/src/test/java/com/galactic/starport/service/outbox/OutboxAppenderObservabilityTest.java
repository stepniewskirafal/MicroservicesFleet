package com.galactic.starport.service.outbox;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galactic.starport.service.Reservation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class OutboxAppenderObservabilityTest {

    private TestObservationRegistry observationRegistry;
    private OutboxWriter outboxWriter;
    private ObjectMapper objectMapper;
    private ReservationEventMapper mapper;
    private Tracer tracer;
    private Propagator propagator;
    private OutboxAppender appender;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        outboxWriter = mock(OutboxWriter.class);
        objectMapper = new ObjectMapper();
        mapper = mock(ReservationEventMapper.class);
        tracer = mock(Tracer.class);
        propagator = mock(Propagator.class);

        appender = new OutboxAppender(outboxWriter, objectMapper, mapper, observationRegistry, tracer, propagator);
        appender.reservationsBinding = "reservations-out";

        // brak kontekstu trace – propagator nie powinien być wywołany
        CurrentTraceContext traceContext = mock(CurrentTraceContext.class);
        when(traceContext.context()).thenReturn(null);
        when(tracer.currentTraceContext()).thenReturn(traceContext);
    }

    @Test
    void publishReservationConfirmedEventCreatesObservationWithExpectedTagsAndStopsIt() {
        // given
        long reservationId = 123L;
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build();

        // mapper zwraca payload – nie testujemy payload biznesowo, tylko obserwację
        when(mapper.toPayload(any(Reservation.class))).thenReturn(ReservationEventPayload.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .build());

        // when
        appender.publishReservationConfirmedEvent(reservation);

        // then - OutboxWriter.save został wywołany
        verify(outboxWriter).save(
                eq("reservations-out"),
                eq("ReservationConfirmed"),
                eq(String.valueOf(reservationId)),
                anyMap(),
                anyMap());

        // and - obserwacja została zarejestrowana z poprawnymi tagami
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.outbox.append")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed");
    }

    @Test
    void publishReservationConfirmedEventMarksObservationAsErrorWhenOutboxWriterThrows() {
        // given
        long reservationId = 999L;
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build();

        when(mapper.toPayload(any(Reservation.class))).thenReturn(ReservationEventPayload.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .build());

        // OutboxWriter rzuca wyjątek
        RuntimeException boom = new RuntimeException("boom");
        doThrow(boom).when(outboxWriter).save(any(), any(), any(), anyMap(), anyMap());

        // when / then - wyjątek propaguje się (observe nie połyka wyjątków)
        RuntimeException ex =
                assertThrows(RuntimeException.class, () -> appender.publishReservationConfirmedEvent(reservation));
        assertSame(boom, ex);

        // and - obserwacja została oznaczona jako error i zatrzymana
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.outbox.append")
                .that()
                .hasError()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed");
    }
}
