package com.galactic.starport.application.service;

import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.application.service.tariff.TariffPolicy;
import com.galactic.starport.domain.model.Reservation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import com.galactic.starport.domain.model.Route;
import com.galactic.starport.domain.port.RoutePlannerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationPersistenceService persistenceService;
    private final TariffPolicy tariffPolicy;
    private final RoutePlannerPort routePlannerClient;
    /**
     * Reserve a bay for the given command.
     * <p>
     * This method orchestrates the allocation of a docking bay (HOLD), calls an
     * external route planner (if requested) outside of any database transaction,
     * computes the final tariff and confirms the reservation. If the external
     * route planner rejects the route or returns an error, the hold is released
     * via {@link ReservationPersistenceService#releaseHold(Reservation)} and an empty Optional is returned.
     */
    public Optional<Reservation> reserveBay(ReserveBayCommand command) {
        // first, allocate a HOLD reservation in its own transaction
        Reservation hold = persistenceService.allocateHold(command);

        // duration in hours for tariff calculation
        long hours = Math.max(1, Duration.between(hold.getPeriod().getStartAt(), hold.getPeriod().getEndAt()).toHours());

        BigDecimal baseFee = tariffPolicy.calculate(command.shipClass(), hours);
        Route route = null;
        // If the caller requested route planning, invoke the external service outside of a transaction.
        if (command.requestRoute()) {
            try {
                // Call the route planner to obtain a risk score. This call should be idempotent and
                // must not be wrapped in a database transaction.
                route = routePlannerClient.planRoute(
                        command.originPortId(),
                        command.starportCode(),
                        command.shipClass(),
                        command.startAt());
                BigDecimal finalFee = baseFee.multiply(BigDecimal.valueOf(1 + route.getRiskScore()/100.0));


                return Optional.of(persistenceService.confirmReservation(hold, route, finalFee));
            } catch (Exception e) {
                // remote call failed or route rejected – release the hold
                persistenceService.releaseHold(hold);
                return Optional.empty();
            }
        }

        // If no route planning is requested, confirm directly
        return Optional.of(persistenceService.confirmReservation(hold, route, baseFee));
    }

}
