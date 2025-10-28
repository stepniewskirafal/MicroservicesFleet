package com.galactic.starport.service;

import com.galactic.starport.repository.ReservationEntity;
import com.galactic.starport.repository.ReservationRepository;
import com.galactic.starport.repository.StarportEntity;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoutePlannerService {

    private final ReservationRepository reservationRepository;

    public Optional<Reservation> addRoute(
            ReserveBayCommand command, Reservation newReservation, StarportEntity starportEntity) {
        if (!command.requestRoute()) {
            return Optional.of(newReservation);
        }

        try {
            Route route = planRoute(command);
            BigDecimal finalFee = newReservation.getFeeCharged().multiply(BigDecimal.valueOf(route.getRiskScore()));
            // potwierdzamy rezerwację z trasą
            Reservation confirmed = confirmFeeAndRoute(newReservation, route, finalFee);
            reservationRepository.save(new ReservationEntity(confirmed, starportEntity));
            return Optional.of(confirmed);
        } catch (Exception ex) {
            // planowanie trasy nie powiodło się – zwalniamy HOLD
            releaseHold(newReservation);
            return Optional.empty();
        }
    }

    public Route planRoute(ReserveBayCommand command) {
        return Route.builder()
                .routeCode("ROUTE-" + command.startStarportCode() + "-" + command.destinationStarportCode() + "-"
                        + ThreadLocalRandom.current().nextInt(100000, 999999))
                .startStarportCode(command.startStarportCode())
                .destinationStarportCode(command.destinationStarportCode())
                .etaLightYears(1.0 + ThreadLocalRandom.current().nextDouble() / 100.0)
                .riskScore(ThreadLocalRandom.current().nextDouble())
                .isActive(true)
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
