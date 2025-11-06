package com.galactic.starport.service;

import com.galactic.starport.repository.StarportEntity;
import com.galactic.starport.repository.StarportRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {
    private final HoldReservationService persistenceService;
    private final ValidateReservationCommandService validateReservationCommandService;
    private final FeeCalculatorService feeCalculatorService;
    private final RoutePlannerService routePlannerService;
    private final StarportRepository starportRepository;
    private final MeterRegistry meterRegistry;

    private Timer reservationTimer;
    private Counter reservationSuccessCounter;
    private Counter reservationErrorCounter;

    @PostConstruct
    void initMetrics() {
        reservationTimer = Timer.builder("reservations.reserve.duration")
                .description("Time spent reserving docking bays")
                .register(meterRegistry);
        reservationSuccessCounter = Counter.builder("reservations.reserve.success")
                .description("Number of successful reservation attempts")
                .register(meterRegistry);
        reservationErrorCounter = Counter.builder("reservations.reserve.errors")
                .description("Number of reservation attempts ending with an error")
                .register(meterRegistry);
    }

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            StarportEntity starport = starportRepository
                    .findByCode(command.destinationStarportCode())
                    .orElseThrow(() -> new StarportNotFoundException(command.destinationStarportCode()));
            validateReservationCommandService.validate(command);
            Reservation newReservation = persistenceService.allocateHold(command, starport);
            newReservation.setFeeCharged(feeCalculatorService.calculateFee(newReservation));

            Optional<Reservation> result = routePlannerService.addRoute(command, newReservation, starport);
            result.ifPresent(reservation -> reservationSuccessCounter.increment());
            return result;
        } catch (RuntimeException ex) {
            reservationErrorCounter.increment();
            throw ex;
        } finally {
            sample.stop(reservationTimer);
        }
    }
}