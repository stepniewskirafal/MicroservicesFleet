package com.galactic.fleet.activityfeed.model;

import com.galactic.fleet.booking.events.ReservationConfirmed;

/**
 * A planned route with its risk score turned into a level — what the classification filter hands to
 * the filter that builds the outgoing record.
 *
 * @param riskLevel {@code LOW}, {@code MEDIUM} or {@code HIGH}
 */
public record ClassifiedRoute(ReservationConfirmed.Route route, String riskLevel) {}
