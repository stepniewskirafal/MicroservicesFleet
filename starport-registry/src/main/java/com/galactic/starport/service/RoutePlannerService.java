package com.galactic.starport.service;

import com.galactic.starport.repository.ReservationRepository;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoutePlannerService {

    private final ReservationRepository reservationRepository;

    public Reservation confirmReservation(ReserveBayCommand command, Reservation newReservation) {
        Route route = null;
        if (command.requestRoute()) {
            try {
                route = planRoute();
                BigDecimal finalFee =
                        newReservation.getFeeCharged().multiply(BigDecimal.valueOf(1 + route.getRiskScore() / 100.0));
                // potwierdzamy rezerwację z trasą
                return confirmFeeAndRoute(newReservation, route, finalFee);
            } catch (Exception ex) {
                // planowanie trasy nie powiodło się – zwalniamy HOLD
                releaseHold(newReservation);
                return null;
            }
        }else {
            return null;
        }
    }

    public Route planRoute() {
        return Route.builder()
                .etaLY(ThreadLocalRandom.current().nextDouble())
                .riskScore(ThreadLocalRandom.current().nextDouble())
                .build();
    }

    public Reservation confirmFeeAndRoute(Reservation newReservation, Route route, BigDecimal finalFee) {
        newReservation.confirmReservation(route, finalFee);
        return newReservation;
    }

    private void releaseHold(Reservation newReservation) {
        reservationRepository.findById(newReservation.getId()).ifPresent(entity -> {
            entity.cancelRevervation();
            reservationRepository.save(entity);
        });
    }
}
