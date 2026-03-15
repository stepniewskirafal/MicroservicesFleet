package com.galactic.traderoute.adapter.out.kafka;

import com.galactic.traderoute.domain.model.RoutePlannedEvent;
import com.galactic.traderoute.port.out.RouteEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StreamBridgeRouteEventPublisher implements RouteEventPublisher {

    private static final String BINDING = "routePlanned-out-0";

    private final StreamBridge streamBridge;

    @Override
    public void publish(RoutePlannedEvent event) {
        var message = MessageBuilder.withPayload(event)
                .setHeader("kafka_messageKey", event.routeId())
                .build();

        boolean sent = streamBridge.send(BINDING, message);
        if (sent) {
            log.info("Published RoutePlannedEvent: routeId={} {} -> {}",
                    event.routeId(), event.originPortId(), event.destinationPortId());
        } else {
            throw new EventPublishingException(
                    "Failed to publish RoutePlannedEvent: routeId=" + event.routeId());
        }
    }
}
