package com.galactic.router.domain;

public class PlannedRoute {
    private final String routeId;
    private final double etaHours;
    private final double riskScore;

    public PlannedRoute(String routeId, double etaHours, double riskScore) {
        this.routeId = routeId;
        this.etaHours = etaHours;
        this.riskScore = riskScore;
    }

    public String getRouteId() {
        return routeId;
    }

    public double getEtaHours() {
        return etaHours;
    }

    public double getRiskScore() {
        return riskScore;
    }
}
