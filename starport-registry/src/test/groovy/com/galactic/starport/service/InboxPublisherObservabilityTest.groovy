package com.galactic.starport.service

import com.galactic.starport.repository.OutboxEventEntity
import com.galactic.starport.repository.OutboxEventJpaRepository
import com.galactic.starport.service.outbox.InboxPublisher
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import org.springframework.cloud.stream.function.StreamBridge
import spock.lang.Specification

import static io.micrometer.core.tck.MeterRegistryAssert.assertThat as assertMeterRegistry
import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat as assertObservation

class InboxPublisherObservabilityTest extends Specification {

    def observationRegistry = TestObservationRegistry.create()
    def meterRegistry = new SimpleMeterRegistry()

    def repo = Mock(OutboxEventJpaRepository)
    def streamBridge = Mock(StreamBridge)

    def batchSize = 50
    def maxAttempts = 10

    def publisher = new InboxPublisher(
            repo,
            streamBridge,
            observationRegistry,
            meterRegistry,
            batchSize,
            maxAttempts
    )

    def "pollAndPublish emits publish observation and records poll duration + batch size metrics (success path)"() {
        given: "1 pending event"
        def e = new OutboxEventEntity()
        e.id = 10L
        e.binding = "reservations-out"
        e.eventType = "ReservationConfirmed"
        e.messageKey = "10"
        e.payloadJson = [reservationId: 10]
        e.headersJson = [contentType: "application/json"]

        repo.lockBatchPending(batchSize) >> [e]
        streamBridge.send("reservations-out", _ as org.springframework.messaging.Message) >> true

        when:
        publisher.pollAndPublish()

        then: "publish obserwacja jest utworzona i ma poprawne tagi low-cardinality"
        assertObservation(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.inbox.publish")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed")

        and: "metryki poll są zarejestrowane"
        assertMeterRegistry(meterRegistry)
                .hasMeterWithName("reservations.inbox.poll.duration")
                .hasMeterWithName("reservations.inbox.poll.batch.size")

        and: "timer ma zapis (co najmniej 1) dla outcome=success i batchSize=1"
        def t = meterRegistry.get("reservations.inbox.poll.duration")
                .tags("outcome", "success", "batchSize", "1")
                .timer()
        t.count() == 1

        and: "summary batch size ma zapis"
        def s = meterRegistry.get("reservations.inbox.poll.batch.size").summary()
        s.count() == 1
        s.totalAmount() == 1d
        s.id.baseUnit == "events"
    }

    def "pollAndPublish records poll duration metric with outcome=error when StreamBridge.send returns false and publish observation is error"() {
        given: "1 pending event"
        def e = new OutboxEventEntity()
        e.id = 11L
        e.binding = "reservations-out"
        e.eventType = "ReservationConfirmed"
        e.messageKey = "11"
        e.payloadJson = [reservationId: 11]
        e.headersJson = [contentType: "application/json"]

        repo.lockBatchPending(batchSize) >> [e]
        streamBridge.send("reservations-out", _ as org.springframework.messaging.Message) >> false

        when:
        publisher.pollAndPublish()

        then: "publish obserwacja ma error i jest zatrzymana"
        assertObservation(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.inbox.publish")
                .that()
                .hasError()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("binding", "reservations-out")
                .hasLowCardinalityKeyValue("eventType", "ReservationConfirmed")

        and: "timer poll jest zarejestrowany dla outcome=error i batchSize=1"
        def t = meterRegistry.get("reservations.inbox.poll.duration")
                .tags("outcome", "error", "batchSize", "1")
                .timer()
        t.count() == 1

        and: "batch size summary jest zarejestrowany (batch != empty, więc record(size) poszedł przed wysyłką)"
        def s = meterRegistry.get("reservations.inbox.poll.batch.size").summary()
        s.count() == 1
        s.totalAmount() == 1d
    }

    def "pollAndPublish records poll duration metric with outcome=empty when no events found and does not emit publish observation"() {
        given:
        repo.lockBatchPending(batchSize) >> []

        when:
        publisher.pollAndPublish()

        then: "timer poll jest zapisany dla outcome=empty i batchSize=0"
        def t = meterRegistry.get("reservations.inbox.poll.duration")
                .tags("outcome", "empty", "batchSize", "0")
                .timer()
        t.count() == 1

        and: "batch size summary nie powstaje (bo record(size) jest tylko gdy batch nie jest pusty)"
        def meters = meterRegistry.meters.findAll { it.id.name == "reservations.inbox.poll.batch.size" }
        meters.isEmpty()
    }
}
