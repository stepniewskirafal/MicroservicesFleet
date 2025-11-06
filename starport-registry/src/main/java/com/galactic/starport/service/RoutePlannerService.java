package com.galactic.starport.service;

import com.galactic.starport.repository.ReservationEntity;
import com.galactic.starport.repository.ReservationRepository;
import com.galactic.starport.repository.StarportEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
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
    private final MeterRegistry meterRegistry;

    private Timer routeConfirmationTimer;
    private Counter routeConfirmationSuccessCounter;
    private Counter routeConfirmationErrorCounter;
    private Timer routePlanningTimer;
    private Counter routePlanningSuccessCounter;
    private Counter routePlanningErrorCounter;

    @PostConstruct
    void initMetrics() {
        routeConfirmationTimer = Timer.builder("reservations.route.confirmation.duration")
                .description("Time spent confirming reservations and routes")
                .register(meterRegistry);
        routeConfirmationSuccessCounter = Counter.builder("reservations.route.confirmation.success")
                .description("Number of successful reservation confirmations")
                .register(meterRegistry);
        routeConfirmationErrorCounter = Counter.builder("reservations.route.confirmation.errors")
                .description("Number of reservation confirmations that failed")
                .register(meterRegistry);
        routePlanningTimer = Timer.builder("reservations.route.planning.duration")
                .description("Time spent planning reservation routes")
                .register(meterRegistry);
        routePlanningSuccessCounter = Counter.builder("reservations.route.planning.success")
                .description("Number of successfully planned reservation routes")
                .register(meterRegistry);
        routePlanningErrorCounter = Counter.builder("reservations.route.planning.errors")
                .description("Number of reservation route planning attempts that failed")
                .register(meterRegistry);
    }

    @Value("${app.bindings.reservations:reservations-out}")
    private String reservationsBinding;

    @Transactional
    public Optional<Reservation> addRoute(
            ReserveBayCommand command, Reservation newReservation, StarportEntity starportEntity) {
        Timer.Sample confirmationSample = Timer.start(meterRegistry);
        try {
            if (!command.requestRoute()) {
                try {
                    Reservation confirmed = confirmFee(newReservation);
                    appendReservationEvent("ReservationConfirmed", confirmed, null);
                    reservationRepository.save(new ReservationEntity(confirmed, starportEntity));
                    routeConfirmationSuccessCounter.increment();
                    return Optional.of(confirmed);
                } catch (Exception ex) {
                    routeConfirmationErrorCounter.increment();
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
                routeConfirmationSuccessCounter.increment();
                return Optional.of(confirmed);
            } catch (Exception ex) {
                routeConfirmationErrorCounter.increment();
                // planowanie trasy nie powiodło się – zwalniamy HOLD
                releaseHold(newReservation);
                return Optional.empty();
            }
        } finally {
            confirmationSample.stop(routeConfirmationTimer);
        }
    }

    public Route planRoute(ReserveBayCommand command) {
        Timer.Sample planningSample = Timer.start(meterRegistry);
        try {
            Route route = Route.builder()
                    .routeCode("ROUTE-" + command.startStarportCode() + "-" + command.destinationStarportCode() + "-"
                            + ThreadLocalRandom.current().nextInt(100000, 999999))
                    .startStarportCode(command.startStarportCode())
                    .destinationStarportCode(command.destinationStarportCode())
                    .etaLightYears(1.0 + ThreadLocalRandom.current().nextDouble() / 100.0)
                    .riskScore(ThreadLocalRandom.current().nextDouble())
                    .isActive(true)
                    .build();
            routePlanningSuccessCounter.increment();
            return route;
        } catch (RuntimeException ex) {
            routePlanningErrorCounter.increment();
            throw ex;
        } finally {
            planningSample.stop(routePlanningTimer);
        }
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