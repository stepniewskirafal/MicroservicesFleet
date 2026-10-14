package com.galactic.fleet.routeplanning.api;

import java.time.Instant;
import lombok.Builder;

@Builder
public record PlannedRoute(String routeId, double etaHours, double riskScore, Instant plannedAt) {}
