package com.galactic.starport.repository;

import com.galactic.starport.service.*;
import java.math.BigDecimal;
import java.util.Optional;

public interface StarportPersistenceFacade {
    Long createHoldReservation(ReserveBayCommand command);

    boolean starportExistsByCode(String code);

    boolean reservationExistsById(Long reservationId);

    Long confirmReservation(Long reservationId, BigDecimal calculatedFee, Optional<Route> route);

    /*  Optional<Starport> starportfindByCode(String code);

    Optional<Customer> customerFindByCustomerCode(String customerCode);

    Optional<DockingBay> dockingBayFindFreeBay(
            String starportCode,
            String shipClass,
            Instant startAt,
            Instant endAt);

    Optional<Ship> shipFindByShipCode(String shipCode);

    Optional<Ship> shipRequireByShipCode(String shipCode);*/
}
