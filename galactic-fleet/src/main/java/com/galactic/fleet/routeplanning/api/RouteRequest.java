package com.galactic.fleet.routeplanning.api;

import lombok.Builder;

/**
 * What a caller knows about the journey: where from, where to, and the class of the ship. What that
 * class can fly (fuel range, cruise speed) is this module's knowledge — the caller does not pass it.
 */
@Builder
public record RouteRequest(String originPortId, String destinationPortId, String shipClass) {}
