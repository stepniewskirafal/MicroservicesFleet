package com.galactic.traderoute.publishing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.galactic.traderoute.planning.RoutePlannedEvent;
import com.galactic.traderoute.publishing.EventPublishingException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

@Execution(ExecutionMode.CONCURRENT)
class RoutePlannedKafkaPublisherTest {

    private StreamBridge streamBridge;
    private RoutePlannedKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        publisher = new RoutePlannedKafkaPublisher(streamBridge);
    }

    @Test
    void should_send_message_to_correct_binding() {
        when(streamBridge.send(any(), any())).thenReturn(true);
        RoutePlannedEvent event = anEvent("ROUTE-ABC12345");

        publisher.on(event);

        verify(streamBridge).send(eq("routePlanned-out-0"), any(Message.class));
    }

    @Test
    void should_set_kafka_message_key_header_to_route_id() {
        when(streamBridge.send(any(), any())).thenReturn(true);
        RoutePlannedEvent event = anEvent("ROUTE-KEY99");

        publisher.on(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<RoutePlannedEvent>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(any(), captor.capture());

        Message<RoutePlannedEvent> sent = captor.getValue();
        assertThat(sent.getHeaders().get("kafka_messageKey")).isEqualTo("ROUTE-KEY99");
    }

    @Test
    void should_map_event_to_wire_dto_payload() {
        when(streamBridge.send(any(), any())).thenReturn(true);
        RoutePlannedEvent event = anEvent("ROUTE-PAY00");

        publisher.on(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<RoutePlannedMessage>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(any(), captor.capture());

        // Payload is the explicit wire DTO (not the domain record), with fields mapped 1:1.
        assertThat(captor.getValue().getPayload()).isEqualTo(RoutePlannedMessage.from(event));
    }

    @Test
    void should_throw_when_send_returns_false() {
        when(streamBridge.send(any(), any())).thenReturn(false);
        RoutePlannedEvent event = anEvent("ROUTE-FAIL01");

        assertThatThrownBy(() -> publisher.on(event))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining("ROUTE-FAIL01");
    }

    private static RoutePlannedEvent anEvent(String routeId) {
        return RoutePlannedEvent.builder()
                .routeId(routeId)
                .originPortId("SP-ORIGIN")
                .destinationPortId("SP-DEST")
                .shipClass("SCOUT")
                .etaHours(10.0)
                .riskScore(0.5)
                .plannedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
