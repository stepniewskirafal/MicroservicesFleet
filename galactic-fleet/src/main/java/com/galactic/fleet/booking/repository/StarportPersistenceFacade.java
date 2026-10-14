package com.galactic.fleet.booking.repository;

import com.galactic.fleet.booking.service.CustomerNotFoundException;
import com.galactic.fleet.booking.service.NoDockingBaysAvailableException;
import com.galactic.fleet.booking.service.Reservation;
import com.galactic.fleet.booking.service.ReserveBayCommand;
import com.galactic.fleet.booking.service.Route;
import com.galactic.fleet.booking.service.ShipNotFoundException;
import com.galactic.fleet.booking.service.StarportNotFoundException;
import java.math.BigDecimal;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The persistence entry point of the module: the only type of this package the service layer uses.
 * Entities and Spring Data repositories stay package-private behind it.
 */
@Repository
@RequiredArgsConstructor
public class StarportPersistenceFacade {
    private final CustomerRepository customerRepository;
    private final StarportRepository starportRepository;
    private final DockingBayRepository dockingBayRepository;
    private final ShipRepository shipRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    /**
     * Picks a free bay ({@code FOR UPDATE SKIP LOCKED}) and stores the reservation as CONFIRMED, with
     * its fee and — when one was planned — its route (ADR-0020, ADR-0046).
     *
     * <p>{@code MANDATORY}: the bay lock must live until the caller's commit, together with the event
     * publication written in that same transaction — so the caller has to bring the transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation createConfirmedReservation(ReserveBayCommand command, BigDecimal fee, Route route) {
        StarportEntity starport = starportRepository
                .findByCode(command.destinationStarportCode())
                .orElseThrow(() -> new StarportNotFoundException(command.destinationStarportCode()));
        CustomerEntity customer = customerRepository
                .findByCustomerCode(command.customerCode())
                .orElseThrow(() -> new CustomerNotFoundException(command.customerCode()));
        ShipEntity ship = shipRepository
                .findByShipCode(command.shipCode())
                .orElseThrow(() -> new ShipNotFoundException(command.shipCode()));
        DockingBayEntity bay = dockingBayRepository
                .findFreeBay(
                        command.destinationStarportCode(),
                        command.shipClass().name(),
                        command.startAt(),
                        command.endAt())
                .orElseThrow(() -> new NoDockingBaysAvailableException(
                        command.destinationStarportCode(),
                        command.shipClass().name(),
                        command.startAt(),
                        command.endAt()));

        ReservationEntity saved =
                reservationRepository.save(new ReservationEntity(starport, bay, customer, ship, command, fee));
        // The route row carries the reservation id, which exists only after save().
        saved.attachRoute(route);
        return reservationMapper.toDomain(saved);
    }

    public boolean customerExistsByCode(String customerCode) {
        return customerRepository.existsByCustomerCode(customerCode);
    }

    public boolean shipExistsByCode(String shipCode) {
        return shipRepository.existsByShipCode(shipCode);
    }

    public boolean starportExistsByCode(String code) {
        return starportRepository.existsByCode(code);
    }

    @Transactional(readOnly = true)
    public Set<String> findAllStarportCodes() {
        return Set.copyOf(starportRepository.findAllCodes());
    }
}
