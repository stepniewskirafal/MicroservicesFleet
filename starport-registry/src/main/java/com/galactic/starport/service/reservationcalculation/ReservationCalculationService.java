package com.galactic.starport.service.reservationcalculation;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.feecalculator.FeeCalculator;
import com.galactic.starport.service.routeplanner.RoutePlanner;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class ReservationCalculationService implements ReservationCalculationFacade {

    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final FeeCalculator feeCalculator;
    private final RoutePlanner routePlanner;

    @Override
    public ReservationCalculation calculate(Long reservationId, ReserveBayCommand command) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(command, "command must not be null");

        CompletableFuture<BigDecimal> feeFuture =
                CompletableFuture.supplyAsync(() -> feeCalculator.calculateFee(command), VIRTUAL_EXECUTOR);

        CompletableFuture<Route> routeFuture =
                CompletableFuture.supplyAsync(() -> routePlanner.calculateRoute(command), VIRTUAL_EXECUTOR);

        BigDecimal calculatedFee = feeFuture.join();
        Route route = routeFuture.join();

        return new ReservationCalculation(reservationId, calculatedFee, route);
    }
}
