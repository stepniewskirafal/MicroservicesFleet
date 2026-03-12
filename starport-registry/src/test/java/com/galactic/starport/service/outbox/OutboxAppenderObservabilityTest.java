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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.util.ReflectionTestUtils;

@Execution(ExecutionMode.CONCURRENT)
class OutboxAppenderObservabilityTest {

    private TestObservationRegistry observationRegistry;
    private OutboxWriter outboxWriter;
    private ObjectMapper objectMapper;
    private ReservationEventMapper mapper;
    private OutboxAppender appender;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        outboxWriter = mock(OutboxWriter.class);
        objectMapper = new ObjectMapper();
        mapper = mock(ReservationEventMapper.class);

        appender = new OutboxAppender(outboxWriter, objectMapper, mapper, observationRegistry);
        ReflectionTestUtils.setField(appender, "reservationsBinding", "reservations-out");
    }

    @Test
    void publishReservationConfirmedEventCreatesObservationWithExpectedTagsAndStopsIt() {
        long reservationId = 123L;
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build();

        when(mapper.toPayload(any(Reservation.class)))
                .thenReturn(ReservationEventPayload.builder()
                        .reservationId(reservationId)
                        .status("CONFIRMED")
                        .build());

        appender.publishReservationConfirmedEvent(reservation);

        verify(outboxWriter)
                .save(
                        eq("reservations-out"),
                        eq("ReservationConfirmed"),
                        eq(String.valueOf(reservationId)),
                        anyMap(),
                        anyMap());

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
        long reservationId = 999L;
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build();

        when(mapper.toPayload(any(Reservation.class)))
                .thenReturn(ReservationEventPayload.builder()
                        .reservationId(reservationId)
                        .status("CONFIRMED")
                        .build());

        RuntimeException boom = new RuntimeException("boom");
        doThrow(boom).when(outboxWriter).save(any(), any(), any(), anyMap(), anyMap());

        RuntimeException ex =
                assertThrows(RuntimeException.class, () -> appender.publishReservationConfirmedEvent(reservation));
        assertSame(boom, ex);

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.outbox.append")
                .that()
                .hasError()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed");
    }
}
