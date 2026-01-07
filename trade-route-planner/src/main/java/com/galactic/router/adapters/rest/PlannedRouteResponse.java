package com.galactic.router.adapters.rest;

/**
 * Odpowiedź zwracana przez endpoint /routes/plan. Zawiera identyfikator trasy,
 * szacowany czas podróży i ryzyko.
 */
public class PlannedRouteResponse {
    private String routeId;
    private double etaHours;
    private double riskScore;

    public PlannedRouteResponse() {}

    public PlannedRouteResponse(String routeId, double etaHours, double riskScore) {
        this.routeId = routeId;
        this.etaHours = etaHours;
        this.riskScore = riskScore;
    }

    public String getRouteId() {
        return routeId;
    }

    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    public double getEtaHours() {
        return etaHours;
    }

    public void setEtaHours(double etaHours) {
        this.etaHours = etaHours;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(double riskScore) {
        this.riskScore = riskScore;
    }
}
