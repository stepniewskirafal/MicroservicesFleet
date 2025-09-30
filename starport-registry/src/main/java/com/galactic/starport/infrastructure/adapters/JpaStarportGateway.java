package com.galactic.starport.infrastructure.adapters;

import com.galactic.starport.domain.enums.ShipClass;
import com.galactic.starport.domain.model.DockingBay;
import com.galactic.starport.domain.model.Reservation;
import com.galactic.starport.domain.model.Starport;
import com.galactic.starport.domain.port.StarportGateway;
import com.galactic.starport.infrastructure.jpa.ReservationJpaRepository;
import com.galactic.starport.infrastructure.jpa.StarportJpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class JpaStarportGateway implements StarportGateway {
    private final StarportJpaRepository starports; // Spring Data
    private final ReservationJpaRepository reservations; // Spring Data

    @Override
    public Optional<Starport> findById(UUID reservationId) {
        return starports.findById(reservationId);
    }

    @Override
    public Optional<Starport> findByCode(String code) {
        return starports.findByCode(code);
    }

    @Override
    public Optional<DockingBay> findFirstFreeBay(String code, ShipClass cls, Instant s, Instant e) {
        return starports.findFirstFreeBay(code, cls.name(), s, e);
    }

    @Override
    public Reservation save(Reservation r) {
        return reservations.save(r);
    }
}
