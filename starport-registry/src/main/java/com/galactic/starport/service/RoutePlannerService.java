package com.galactic.starport.service;

import com.galactic.starport.domain.Reservation;
import com.galactic.starport.domain.ReserveBayCommand;
import com.galactic.starport.domain.Route;
import com.galactic.starport.repository.ReservationPersistenceAdapter;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoutePlannerService {

    private final ReservationPersistenceAdapter reservationPersistenceAdapter;

    public Optional<Reservation> addRoute(ReserveBayCommand command, Reservation reservation) {
        if (!command.requestRoute()) {
            return Optional.of(reservation);
        }

        try {
            Route route = planRoute(command);
            BigDecimal finalFee = reservation.getFeeCharged().multiply(BigDecimal.valueOf(route.getRiskScore()));
            Reservation confirmed = confirmFeeAndRoute(reservation, route, finalFee);
            Reservation saved = reservationPersistenceAdapter.save(confirmed);
            return Optional.of(saved);
        } catch (Exception ex) {
            releaseHold(reservation);
            return Optional.empty();
        }
    }

    public Route planRoute(ReserveBayCommand command) {
        return Route.builder()
                .routeCode(
                        "ROUTE-" + command.startStarportCode() + "-" + command.destinationStarportCode() + "-"
                                + ThreadLocalRandom.current().nextInt(100000, 999999))
                .startStarportCode(command.startStarportCode())
                .destinationStarportCode(command.destinationStarportCode())
                .etaLightYears(1.0 + ThreadLocalRandom.current().nextDouble() / 100.0)
                .riskScore(ThreadLocalRandom.current().nextDouble())
                .isActive(true)
                .build();
    }

    public Reservation confirmFeeAndRoute(Reservation reservation, Route route, BigDecimal finalFee) {
        reservation.confirmReservation(route, finalFee);
        return reservation;
    }

    private void releaseHold(Reservation reservation) {
        reservationPersistenceAdapter.cancelReservation(reservation.getId());
    }
}
