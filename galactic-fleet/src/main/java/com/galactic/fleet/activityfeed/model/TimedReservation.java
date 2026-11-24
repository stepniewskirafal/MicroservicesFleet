package com.galactic.fleet.activityfeed.model;

import com.galactic.fleet.booking.events.ReservationConfirmed;

/**
 * A confirmed reservation with the length of the stay worked out — what the duration filter hands
 * to the filter that builds the outgoing record.
 *
 * @param durationHours whole hours between start and end; {@code 0} when either is missing
 */
public record TimedReservation(ReservationConfirmed reservation, long durationHours) {}
