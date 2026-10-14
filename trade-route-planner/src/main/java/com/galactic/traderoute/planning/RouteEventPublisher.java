package com.galactic.traderoute.planning;

public interface RouteEventPublisher {
    void publish(RoutePlannedEvent event);
}
