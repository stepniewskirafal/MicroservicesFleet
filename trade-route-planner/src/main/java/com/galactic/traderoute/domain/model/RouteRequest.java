package com.galactic.traderoute.domain.model;

import lombok.Builder;

@Builder
public record RouteRequest(
        String originPortId, String destinationPortId, String shipClass, double fuelRangeLY) {}
