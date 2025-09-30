package com.galactic.starport.domain.port;

import com.galactic.starport.domain.enums.ShipClass;
import com.galactic.starport.domain.model.DockingBay;
import com.galactic.starport.domain.model.Reservation;
import com.galactic.starport.domain.model.Starport;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// domain.port
public interface StarportGateway {
    Optional<Starport> findById(UUID reservationId);

    Optional<Starport> findByCode(String code);

    Optional<DockingBay> findFirstFreeBay(String starportCode, ShipClass shipClass, Instant startAt, Instant endAt);

    Reservation save(Reservation r); // jeśli chcesz zapisy przez ten sam gateway
}
