package com.galactic.fleet.activityfeed.filter;

import com.galactic.fleet.activityfeed.model.TimedReservation;
import com.galactic.fleet.booking.events.ReservationConfirmed;
import java.time.Duration;
import java.util.function.Function;

/** Works out how many whole hours the ship stays berthed. */
public class StayDurationFilter implements Function<ReservationConfirmed, TimedReservation> {

    @Override
    public TimedReservation apply(ReservationConfirmed event) {
        long durationHours = 0;
        if (event.startAt() != null && event.endAt() != null) {
            durationHours = Duration.between(event.startAt(), event.endAt()).toHours();
        }
        return new TimedReservation(event, durationHours);
    }
}
