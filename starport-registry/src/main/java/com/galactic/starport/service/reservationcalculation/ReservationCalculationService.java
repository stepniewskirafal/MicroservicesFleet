package com.galactic.starport.service.reservationcalculation;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.feecalculator.FeeCalculator;
import com.galactic.starport.service.routeplanner.RoutePlanner;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class ReservationCalculationService implements ReservationCalculationFacade {

    private static final ContextSnapshotFactory CONTEXT_SNAPSHOT_FACTORY =
            ContextSnapshotFactory.builder().build();

    // Virtual-thread executor wrapped with Micrometer context propagation: at submit time it captures
    // the caller's ThreadLocal context — crucially the active Observation/trace scope — and restores
    // it on the worker thread. Without this, calculateRoute (and feeCalculator) run context-free, so
    // the reservations.route.plan Observation finds no parent and starts a NEW root trace, detached
    // from the inbound HTTP request trace (ADR-0017).
    private static final ExecutorService VIRTUAL_EXECUTOR = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), CONTEXT_SNAPSHOT_FACTORY::captureAll);

    private static final String OBSERVATION_NAME = "reservations.calculate";

    private final FeeCalculator feeCalculator;
    private final RoutePlanner routePlanner;
    private final ObservationRegistry observationRegistry;

    @Override
    public ReservationCalculation calculate(Long reservationId, ReserveBayCommand command) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // A span that GROUPS the two parallel branches (fee + route) under one node in Tempo. Without it
        // the fee and route.plan child spans hang directly off the inbound HTTP span as bare siblings, so
        // the fan-out — and the wall-clock the calculation costs — is not visible as a phase. The
        // observation scope is open when the branches are submitted, so ContextExecutorService captures
        // it and their spans nest under this one.
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("starport", Objects.toString(command.destinationStarportCode(), "unknown"))
                .lowCardinalityKeyValue("shipClass", command.shipClass().name())
                .observe(() -> doCalculate(reservationId, command));
    }

    private ReservationCalculation doCalculate(Long reservationId, ReserveBayCommand command) {
        CompletableFuture<BigDecimal> feeFuture =
                CompletableFuture.supplyAsync(() -> feeCalculator.calculateFee(command), VIRTUAL_EXECUTOR);

        CompletableFuture<Route> routeFuture =
                CompletableFuture.supplyAsync(() -> routePlanner.calculateRoute(command), VIRTUAL_EXECUTOR);

        BigDecimal calculatedFee = join(feeFuture);
        Route route = join(routeFuture);

        return new ReservationCalculation(reservationId, calculatedFee, route);
    }

    /**
     * Awaits a future and unwraps the {@link CompletionException} that {@link CompletableFuture#join()} wraps
     * around any failure. Without this, a domain exception thrown inside the async task (e.g.
     * {@code RouteUnavailableException} or {@code NoDockingBaysAvailableException}) reaches the caller as a
     * {@code CompletionException}, slips past the typed {@code catch} blocks in {@code ReservationService} /
     * {@code GlobalExceptionHandler}, and is mapped to HTTP 500 instead of the intended 409.
     */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }
}
