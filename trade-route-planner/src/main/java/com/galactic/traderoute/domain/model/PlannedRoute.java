package com.galactic.traderoute.domain.model;

import lombok.Builder;

@Builder
public record PlannedRoute(String routeId, double etaHours, double riskScore) {}
