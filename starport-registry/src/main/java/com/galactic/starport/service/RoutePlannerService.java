package com.galactic.starport.service;

import com.galactic.starport.repository.ReservationEntity;
import com.galactic.starport.repository.ReservationRepository;
import com.galactic.starport.repository.StarportEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutePlannerService {

    private final ReservationRepository reservationRepository;
    private final OutboxWriter outboxWriter;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    @Value("${app.bindings.reservations.name:reservations-out}")
    String reservationsBinding;

    // --- METRICS ---
    private Timer routePlanningTimer;
    private Counter routePlanningSuccessCounter;
    private Counter routePlanningErrorCounter;

    private Timer routeConfirmationTimer;
    private Counter routeConfirmationSuccessCounter;
    private Counter routeConfirmationErrorCounter;

    @PostConstruct
    public void initMetrics() {
        routePlanningTimer = Timer.builder("reservations.route.planning.duration")
                .description("Time to plan a route for a reservation")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(meterRegistry);

        routePlanningSuccessCounter = Counter.builder("reservations.route.planning.success")
                .description("Successful route planning count")
                .register(meterRegistry);

        routePlanningErrorCounter = Counter.builder("reservations.route.planning.errors")
                .description("Failed route planning count")
                .register(meterRegistry);

        routeConfirmationTimer = Timer.builder("reservations.route.confirmation.duration")
                .description("Time to confirm fee and persist reservation")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(meterRegistry);

        routeConfirmationSuccessCounter = Counter.builder("reservations.route.confirmation.success")
                .description("Successful reservation confirmation count")
                .register(meterRegistry);

        routeConfirmationErrorCounter = Counter.builder("reservations.route.confirmation.errors")
                .description("Failed reservation confirmation count")
                .register(meterRegistry);
    }

    public Optional<Reservation> addRoute(ReserveBayCommand command,
                                          Reservation newReservation,
                                          StarportEntity starportEntity) {
        // START -> przed try/catch
        Timer.Sample confirmSample = Timer.start(meterRegistry);
        try {
            Route route = command.requestRoute() ? planRoute(command) : null;

            BigDecimal finalFee = confirmFee(newReservation);
            Reservation confirmed = confirmFeeAndRoute(newReservation, route, finalFee);

            reservationRepository.save(new ReservationEntity(confirmed, starportEntity));

            appendReservationEvent("ReservationConfirmed", confirmed, route);
            routeConfirmationSuccessCounter.increment();
            return Optional.of(confirmed);
        } catch (Exception ex) {
            routeConfirmationErrorCounter.increment();
            releaseHold(newReservation);
            return Optional.empty();
        } finally {
            // STOP -> zawsze
            confirmSample.stop(routeConfirmationTimer);
        }
    }


    public Route planRoute(ReserveBayCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Uproszczony builder – bez .hops(...)
            Route route = Route.builder()
                    .startStarportCode(command.startStarportCode())
                    .destinationStarportCode(command.destinationStarportCode())
                    // opcjonalnie: .routeCode(command.startStarportCode() + "-" + command.destinationStarportCode())
                    .build();
            routePlanningSuccessCounter.increment();
            return route;
        } catch (RuntimeException ex) {
            routePlanningErrorCounter.increment();
            throw ex;
        } finally {
            sample.stop(routePlanningTimer);
        }
    }

    private BigDecimal confirmFee(Reservation r) {
        return r.getFeeCharged(); // fee policzone wcześniej
    }

    // ZAMIANA buildera na metody mutujące z klasy Reservation
    private Reservation confirmFeeAndRoute(Reservation r, Route route, BigDecimal finalFee) {
        if (route != null) {
            r.confirmReservationWithRoute(route, finalFee);
        } else {
            r.setFeeCharged(finalFee);
            r.confirmReservationWithoutRoute();
        }
        return r;
    }

    private void releaseHold(Reservation r) {
        reservationRepository.findById(r.getId()).ifPresent(re ->
            appendReservationEvent("ReservationReleased", r, null)
        );
    }

    // Dopasowanie do sygnatury OutboxWriter#append(String, String, String, Map, Map)
    private void appendReservationEvent(String eventName, Reservation reservation, Route route) {
        String payload = "reservationId=" + reservation.getId()
                + ",status=" + reservation.getStatus()
                + (route != null
                ? (",start=" + route.getStartStarportCode()
                + ",destination=" + route.getDestinationStarportCode())
                : "");
        Map<String, Object> headers = Map.of("eventName", eventName);
        Map<String, Object> attributes = Map.of(
                "reservationId", reservation.getId() == null ? "null" : reservation.getId().toString()
        );

        outboxWriter.append(reservationsBinding, eventName, payload, headers, attributes);
    }
}
