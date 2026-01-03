package com.galactic.starport.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.galactic.starport.service.outbox.OutboxAppender
import com.galactic.starport.service.outbox.OutboxWriter
import com.galactic.starport.service.outbox.ReservationEventMapper
import com.galactic.starport.service.outbox.ReservationEventPayload
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import spock.lang.Specification

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat as assertObservation

class OutboxAppenderObservabilityTest extends Specification {

    def observationRegistry = TestObservationRegistry.create()

    def outboxWriter = Mock(OutboxWriter)
    def objectMapper = new ObjectMapper()
    def mapper = Mock(ReservationEventMapper)

    def tracer = Mock(Tracer)
    def propagator = Mock(Propagator)

    def appender = new OutboxAppender(
            outboxWriter,
            objectMapper,
            mapper,
            observationRegistry,
            tracer,
            propagator
    )

    def setup() {
        appender.reservationsBinding = "reservations-out"
    }

    def "publishReservationConfirmedEvent creates observation with expected tags and stops it"() {
        given:
        def reservationId = 123L
        def reservation = Reservation.builder()
                .id(reservationId)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build()

        and: "mapper zwraca dowolny payload — tu nie testujemy payloadu biznesowo, tylko obserwację"
        mapper.toPayload(_ as Reservation) >> ReservationEventPayload.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .build()

        and: "brak kontekstu trace — propagator nie powinien być wywołany"
        tracer.currentTraceContext() >> Stub(io.micrometer.tracing.CurrentTraceContext) {
            context() >> null
        }

        when:
        appender.publishReservationConfirmedEvent(reservation)

        then: "OutboxWriter.save został wywołany (tylko po to, aby obserwacja miała co obserwować)"
        1 * outboxWriter.save(
                "reservations-out",
                "ReservationConfirmed",
                String.valueOf(reservationId),
                _ as Map,
                { Map h -> h.get("contentType") == "application/json" }
        )

        and: "obserwacja została zarejestrowana z poprawnymi tagami"
        assertObservation(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.outbox.append")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed")
    }

    def "publishReservationConfirmedEvent marks observation as error when outboxWriter throws"() {
        given:
        def reservationId = 999L
        def reservation = Reservation.builder()
                .id(reservationId)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build()

        mapper.toPayload(_ as Reservation) >> ReservationEventPayload.builder()
                .reservationId(reservationId)
                .status("CONFIRMED")
                .build()

        tracer.currentTraceContext() >> Stub(io.micrometer.tracing.CurrentTraceContext) {
            context() >> null
        }

        and: "OutboxWriter rzuca wyjątek"
        def boom = new RuntimeException("boom")
        outboxWriter.save(_, _, _, _, _) >> { throw boom }

        when:
        appender.publishReservationConfirmedEvent(reservation)

        then: "wyjątek propaguje się do testu (observe nie połyka wyjątków)"
        def ex = thrown(RuntimeException)
        ex.is(boom)

        and: "obserwacja została oznaczona jako error i zatrzymana"
        assertObservation(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.outbox.append")
                .that()
                .hasError()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed")
    }
}
