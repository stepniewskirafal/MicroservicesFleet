package com.galactic.traderoute.web;

public record RouteRejectedResponse(String error, String reason, String details) {}
