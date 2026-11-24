package com.galactic.fleet.activityfeed.filter;

import com.galactic.fleet.activityfeed.model.ClassifiedRoute;
import com.galactic.fleet.booking.events.ReservationConfirmed;
import java.util.function.Function;

/** Turns the numeric risk score of a route into a level a consumer can filter on. */
public class RiskClassificationFilter implements Function<ReservationConfirmed.Route, ClassifiedRoute> {

    private static final double RISK_THRESHOLD_LOW = 0.3;
    private static final double RISK_THRESHOLD_HIGH = 0.7;

    @Override
    public ClassifiedRoute apply(ReservationConfirmed.Route route) {
        return new ClassifiedRoute(route, classify(route.riskScore()));
    }

    private static String classify(double riskScore) {
        if (riskScore < RISK_THRESHOLD_LOW) {
            return "LOW";
        } else if (riskScore < RISK_THRESHOLD_HIGH) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }
}
