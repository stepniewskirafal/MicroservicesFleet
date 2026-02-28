package com.galactic.traderoute.adapter.in.rest;

import lombok.Builder;

@Builder
public record PlanRouteResponse(String routeId, double etaHours, double riskScore, String correlationId) {}
