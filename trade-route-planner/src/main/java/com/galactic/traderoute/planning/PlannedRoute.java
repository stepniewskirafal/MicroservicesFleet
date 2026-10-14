package com.galactic.traderoute.planning;

import lombok.Builder;

@Builder
public record PlannedRoute(String routeId, double etaHours, double riskScore) {}
