package com.galactic.fleet.booking.service;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Route {
    private Long id;
    private String routeCode;
    private String startStarportCode;
    private String destinationStarportCode;
    private Double etaHours;
    private Double riskScore;
    private boolean isActive;

    /** When the routeplanning module computed this route; {@code null} for a route read back from the database. */
    private java.time.Instant plannedAt;
}
