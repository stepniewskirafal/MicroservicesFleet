package com.galactic.starport.service.reservationcalculation;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.feecalculator.FeeCalculator;
import com.galactic.starport.service.routeplanner.RoutePlanner;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class ReservationCalculationService implements ReservationCalculationFacade {

    private final FeeCalculator feeCalculator;
    private final RoutePlanner routePlanner;

    @Override
    public ReservationCalculation calculate(Long reservationId, ReserveBayCommand command) {
        BigDecimal calculatedFee = feeCalculator.calculateFee(command);
        Route route = routePlanner.calculateRoute(command);
        return new ReservationCalculation(reservationId, calculatedFee, route);
    }
}
