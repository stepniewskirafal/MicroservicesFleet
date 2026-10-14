package com.galactic.traderoute.planning;

import lombok.Builder;

@Builder
public record RouteRequest(String originPortId, String destinationPortId, String shipClass, double fuelRangeLY) {}
