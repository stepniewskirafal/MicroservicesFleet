package com.galactic.traderoute.web;

import lombok.Builder;

@Builder
public record PlanRouteResponse(String routeId, double etaHours, double riskScore, String correlationId) {}
