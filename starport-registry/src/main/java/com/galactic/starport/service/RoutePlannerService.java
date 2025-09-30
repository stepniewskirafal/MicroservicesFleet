package com.galactic.starport.service;

import com.galactic.starport.repository.ReservationEntity;
import com.galactic.starport.repository.ReservationRepository;
import com.galactic.starport.repository.StarportEntity;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoutePlannerService {

    private final ReservationRepository reservationRepository;
    private final OutboxWriter outbox;

    @Value("${app.bindings.reservations:reservations-out}")
    private String reservationsBinding;

    @Transactional
    public Optional<Reservation> addRoute(
            ReserveBayCommand command, Reservation newReservation, StarportEntity starportEntity) {
        if (!command.requestRoute()) {
            try {
                Reservation confirmed = confirmFee(newReservation);
                appendReservationEvent("ReservationConfirmed", confirmed, null);
                reservationRepository.save(new ReservationEntity(confirmed, starportEntity));
                return Optional.of(confirmed);
            } catch (Exception ex) {
                releaseHold(newReservation);
                return Optional.empty();
            }
        }

        try {
            Route route = planRoute(command);
            BigDecimal finalFee = newReservation.getFeeCharged().multiply(BigDecimal.valueOf(route.getRiskScore()));
            Reservation confirmed = confirmFeeAndRoute(newReservation, route, finalFee);
            reservationRepository.save(new ReservationEntity(confirmed, starportEntity));
            appendReservationEvent("ReservationConfirmed", confirmed, route);
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

    public Reservation confirmFee(Reservation newReservation) {
        newReservation.confirmReservationWithoutRoute();
        return newReservation;
    }
    public Reservation confirmFeeAndRoute(Reservation newReservation, Route route, BigDecimal finalFee) {
        newReservation.confirmReservationWithRoute(route, finalFee);
        return newReservation;
    }

    private void releaseHold(Reservation newReservation) {
        reservationRepository.findById(newReservation.getId()).ifPresent(entity -> {
            entity.cancelRevervation();
            reservationRepository.save(entity);
        });
    }

    private void appendReservationEvent(String eventType, Reservation reservation, Route routeOrNull) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reservationId", reservation.getId());
        payload.put("fee", reservation.getFeeCharged());
        if (routeOrNull != null) {
            payload.put("routeCode", routeOrNull.getRouteCode());
            payload.put("riskScore", routeOrNull.getRiskScore());
        }

        Map<String, Object> headers = Map.of("contentType", "application/json");

        outbox.append(
                reservationsBinding,
                eventType,
                String.valueOf(reservation.getId()), // message key
                payload,
                headers);
    }
}
