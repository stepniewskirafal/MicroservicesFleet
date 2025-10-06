package com.galactic.starport.infrastructure.adapters;

import com.galactic.starport.domain.port.RoutePlannerPort;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * No-op (stub) adapter for route planning. - Keeps the app startable in dev/test. - Returns null by
 * default (YAGNI). If you prefer to always have some ID, flip the flag RETURN_FAKE_ID to true.
 */
@Component
@Primary
public class NoOpRoutePlannerAdapter implements RoutePlannerPort {

    private static final boolean RETURN_FAKE_ID = false; // set true if you want a dummy routeId

    @Override
    public String requestRoute(String shipId, String originPortCode, String destinationPortCode) {
        if (!RETURN_FAKE_ID) {
            return null; // let domain handle "no route planned" as optional
        }
        return "route-" + UUID.randomUUID();
    }
}
