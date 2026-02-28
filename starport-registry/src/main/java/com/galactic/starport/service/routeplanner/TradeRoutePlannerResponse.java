package com.galactic.starport.service.routeplanner;

record TradeRoutePlannerResponse(String routeId, double etaHours, double riskScore, String correlationId) {}
