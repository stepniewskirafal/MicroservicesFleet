package com.galactic.traderoute.port.out;

import com.galactic.traderoute.domain.model.RoutePlannedEvent;

public interface RouteEventPublisher {
    void publish(RoutePlannedEvent event);
}
