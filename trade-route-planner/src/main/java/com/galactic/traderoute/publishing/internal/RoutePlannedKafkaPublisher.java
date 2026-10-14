package com.galactic.traderoute.publishing.internal;

import com.galactic.traderoute.planning.RoutePlannedEvent;
import com.galactic.traderoute.publishing.EventPublishingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Bridges the {@code planning} module's {@link RoutePlannedEvent} to the Kafka binding.
 *
 * <p>The listener is deliberately SYNCHRONOUS (plain {@code @EventListener}, not
 * {@code @ApplicationModuleListener}): the external contract requires that a publish failure
 * surfaces in the same HTTP request (502 + {@code routes.publish.failed}, success metric only after
 * the event is on the wire) and that the producer span/MDC context of the planning thread carries
 * over to the send. There is no transaction here, so after-commit semantics would buy nothing and
 * would silently swallow the failure (ADR-0041).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RoutePlannedKafkaPublisher {

    private static final String BINDING = "routePlanned-out-0";

    private final StreamBridge streamBridge;

    @EventListener
    public void on(RoutePlannedEvent event) {
        // Map the domain event to an explicit wire DTO so the Kafka payload contract is decoupled
        // from the domain record — a domain refactor won't silently break telemetry's consumer.
        var message = MessageBuilder.withPayload(RoutePlannedMessage.from(event))
                .setHeader("kafka_messageKey", event.routeId())
                .build();

        boolean sent = streamBridge.send(BINDING, message);
        if (sent) {
            log.info(
                    "Published RoutePlannedEvent: routeId={} {} -> {}",
                    event.routeId(),
                    event.originPortId(),
                    event.destinationPortId());
        } else {
            throw new EventPublishingException("Failed to publish RoutePlannedEvent: routeId=" + event.routeId());
        }
    }
}
