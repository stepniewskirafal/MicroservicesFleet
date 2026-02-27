package com.galactic.starport.service;

import com.galactic.starport.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.validate.ValidationException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReservationService {

    private final CreateHoldReservationService createHoldReservationService;
    private final ConfirmReservationService confirmReservationService;
    private final ReserveBayValidator reservationValidator;
    private final FeeCalculatorService feeCalculatorService;
    private final RoutePlannerService routePlannerService;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    // === BUSINESS METRICS ===

    // 1. Revenue tracking - KLUCZOWA METRYKA BIZNESOWA
    private final DistributionSummary revenuePerReservation;
    private final Counter totalRevenueCounter;

    // 2. Conversion funnel - gdzie tracimy klientów?
    private final Counter reservationStartedCounter;
    private final Counter reservationCompletedCounter;

    // 3. Customer segmentation - jacy klienci są najcenniejsi?
    private final Map<String, Counter> revenueByShipClassCounters;
    private final Map<String, Counter> reservationsByStarportCounters;

    // 4. Performance SLO - czy spełniamy obietnice biznesowe?
    private final Timer reservationLatencyTimer;
    private final Counter sloViolationsCounter;

    // 5. Active business state - co dzieje się TERAZ?
    private final AtomicInteger activeReservationsInProgress;
    private final AtomicInteger holdReservationsCount;
    private final AtomicLong potentialRevenueInProgress; // Revenue "in flight"

    // 6. Error impact tracking - ile tracimy przez błędy?
    private final Counter lostRevenueCounter;
    private final DistributionSummary lostRevenuePerError;

    public ReservationService(
            CreateHoldReservationService createHoldReservationService,
            ConfirmReservationService confirmReservationService,
            ReserveBayValidator reservationValidator,
            FeeCalculatorService feeCalculatorService,
            RoutePlannerService routePlannerService,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.createHoldReservationService = createHoldReservationService;
        this.confirmReservationService = confirmReservationService;
        this.reservationValidator = reservationValidator;
        this.feeCalculatorService = feeCalculatorService;
        this.routePlannerService = routePlannerService;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;

        // 1. Revenue tracking
        this.revenuePerReservation = DistributionSummary.builder("business.revenue.per.reservation")
                .description("Revenue generated per individual reservation")
                .baseUnit("PLN")
                .publishPercentileHistogram()
                .serviceLevelObjectives(50, 100, 200, 500, 1000) // Business thresholds
                .register(meterRegistry);

        this.totalRevenueCounter = Counter.builder("business.revenue.total")
                .description("Total accumulated revenue from all reservations")
                .baseUnit("PLN")
                .register(meterRegistry);

        // 2. Conversion funnel
        this.reservationStartedCounter = Counter.builder("business.funnel.reservation.started")
                .description("Number of reservation attempts initiated")
                .register(meterRegistry);

        this.reservationCompletedCounter = Counter.builder("business.funnel.reservation.completed")
                .description("Number of successfully completed reservations")
                .register(meterRegistry);

        // 3. Customer segmentation
        this.revenueByShipClassCounters = new ConcurrentHashMap<>();
        this.reservationsByStarportCounters = new ConcurrentHashMap<>();

        // Pre-initialize for known ship classes
        for (ReserveBayCommand.ShipClass shipClass : ReserveBayCommand.ShipClass.values()) {
            this.revenueByShipClassCounters.put(
                    shipClass.name(),
                    Counter.builder("business.revenue.by.ship.class")
                            .description("Revenue segmented by ship class")
                            .baseUnit("PLN")
                            .tag("shipClass", shipClass.name())
                            .tag("hourlyRate", shipClass.hourlyRate().toString())
                            .register(meterRegistry)
            );
        }

        // 4. Performance SLO - Business requirement: 95% < 200ms
        this.reservationLatencyTimer = Timer.builder("business.slo.reservation.latency")
                .description("End-to-end reservation latency (SLO: 95% < 200ms)")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(50),   // P50 target
                        Duration.ofMillis(100),  // P75 target
                        Duration.ofMillis(200),  // P95 target (SLO)
                        Duration.ofMillis(500),  // P99 target
                        Duration.ofMillis(1000)  // Max acceptable
                )
                .register(meterRegistry);

        this.sloViolationsCounter = Counter.builder("business.slo.violations")
                .description("Number of times SLO was violated (>200ms)")
                .tag("slo", "latency_200ms")
                .register(meterRegistry);

        // 5. Active business state - Real-time gauges
        this.activeReservationsInProgress = new AtomicInteger(0);
        this.holdReservationsCount = new AtomicInteger(0);
        this.potentialRevenueInProgress = new AtomicLong(0);

        Gauge.builder("business.reservations.active.count",
                        activeReservationsInProgress, AtomicInteger::get)
                .description("Number of reservations currently being processed")
                .register(meterRegistry);

        Gauge.builder("business.reservations.hold.count",
                        holdReservationsCount, AtomicInteger::get)
                .description("Number of reservations in HOLD state (not yet confirmed)")
                .register(meterRegistry);

        Gauge.builder("business.revenue.potential.in.progress",
                        potentialRevenueInProgress, AtomicLong::get)
                .description("Potential revenue from reservations currently being processed")
                .baseUnit("PLN")
                .register(meterRegistry);

        // 6. Error impact tracking
        this.lostRevenueCounter = Counter.builder("business.revenue.lost.total")
                .description("Total revenue lost due to failed reservations")
                .baseUnit("PLN")
                .register(meterRegistry);

        this.lostRevenuePerError = DistributionSummary.builder("business.revenue.lost.per.error")
                .description("Revenue lost per individual error")
                .baseUnit("PLN")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        // Track funnel start
        reservationStartedCounter.increment();
        activeReservationsInProgress.incrementAndGet();

        // Start latency tracking for SLO
        long startTime = System.nanoTime();

        try {
            return Observation.createNotStarted("reservations.reserve", observationRegistry)
                    .lowCardinalityKeyValue("starport", command.destinationStarportCode())
                    .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                    .lowCardinalityKeyValue("requestRoute", String.valueOf(command.requestRoute()))
                    .observe(() -> {
                        try {
                            log.info("Reserving bay for command: {}", command);

                            // Validation
                            reservationValidator.validate(command);

                            // Create HOLD
                            Long reservationId = createHoldReservationService.createHoldReservation(command);
                            holdReservationsCount.incrementAndGet();

                            // Calculate fee & route
                            ReservationCalculation calc = getReservationCalculation(reservationId, command);

                            // Track potential revenue
                            long potentialRevenue = calc.calculatedFee().longValue();
                            potentialRevenueInProgress.addAndGet(potentialRevenue);

                            // Confirm reservation
                            Reservation reservation = confirmReservationService.confirmReservation(
                                    calc, command.destinationStarportCode());

                            // === BUSINESS SUCCESS METRICS ===

                            // 1. Track revenue
                            double revenue = calc.calculatedFee().doubleValue();
                            revenuePerReservation.record(revenue);
                            totalRevenueCounter.increment(revenue);

                            // 2. Segment by ship class
                            revenueByShipClassCounters
                                    .computeIfAbsent(command.shipClass().name(),
                                            className -> Counter.builder("business.revenue.by.ship.class")
                                                    .baseUnit("PLN")
                                                    .tag("shipClass", className)
                                                    .register(meterRegistry))
                                    .increment(revenue);

                            // 3. Track by starport (market analysis)
                            reservationsByStarportCounters
                                    .computeIfAbsent(command.destinationStarportCode(),
                                            starport -> Counter.builder("business.reservations.by.starport")
                                                    .tag("starport", starport)
                                                    .register(meterRegistry))
                                    .increment();

                            // 4. Funnel completion
                            reservationCompletedCounter.increment();

                            // 5. Clean up state
                            holdReservationsCount.decrementAndGet();
                            potentialRevenueInProgress.addAndGet(-potentialRevenue);

                            // 6. Check SLO compliance
                            long durationNanos = System.nanoTime() - startTime;
                            long durationMillis = durationNanos / 1_000_000;
                            reservationLatencyTimer.record(Duration.ofNanos(durationNanos));

                            if (durationMillis > 200) {
                                sloViolationsCounter.increment();
                                log.warn("SLO VIOLATION: Reservation took {}ms (SLO: 200ms)", durationMillis);
                            }

                            return Optional.of(reservation);

                        } catch (ValidationException e) {
                            handleBusinessError("validation_failed", command, e);
                            throw e;
                        } catch (StarportNotFoundException e) {
                            handleBusinessError("starport_not_found", command, e);
                            throw e;
                        } catch (Exception e) {
                            handleBusinessError("system_error", command, e);
                            throw e;
                        }
                    });

        } finally {
            activeReservationsInProgress.decrementAndGet();
        }
    }

    private void handleBusinessError(String errorReason, ReserveBayCommand command, Exception e) {
        // Track funnel failure
        Counter.builder("business.funnel.reservation.failed")
                .tag("reason", errorReason)
                .tag("starport", command.destinationStarportCode())
                .tag("shipClass", command.shipClass().name())
                .register(meterRegistry)
                .increment();

        // Estimate lost revenue (use ship class hourly rate)
        long estimatedHours = Duration.between(command.startAt(), command.endAt()).toHours();
        estimatedHours = Math.max(1, estimatedHours);
        double lostRevenue = command.shipClass().hourlyRate()
                .multiply(BigDecimal.valueOf(estimatedHours))
                .doubleValue();

        lostRevenueCounter.increment(lostRevenue);
        lostRevenuePerError.record(lostRevenue);

        // Clean up state if needed
        if (holdReservationsCount.get() > 0) {
            holdReservationsCount.decrementAndGet();
        }

        long potentialInProgress = potentialRevenueInProgress.get();
        if (potentialInProgress >= (long) lostRevenue) {
            potentialRevenueInProgress.addAndGet(-(long) lostRevenue);
        }

        log.error("Business impact: Lost ~{} PLN due to {} for reservation at starport {}",
                lostRevenue, errorReason, command.destinationStarportCode(), e);
    }

    private ReservationCalculation getReservationCalculation(Long reservationId, ReserveBayCommand command) {
        BigDecimal calculatedFee = feeCalculatorService.calculateFee(command);
        Route route = routePlannerService.calculateRoute(command);
        return new ReservationCalculation(reservationId, calculatedFee, route);
    }
}