package com.galactic.starport.repository;

import com.galactic.starport.service.CustomerNotFoundException;
import com.galactic.starport.service.NoDockingBaysAvailableException;
import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.ShipNotFoundException;
import com.galactic.starport.service.StarportNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class JpaStarportRepository implements StarportPersistenceFacade {
    private final CustomerRepository customerRepository;
    private final StarportRepository starportRepository;
    private final DockingBayRepository dockingBayRepository;
    private final ShipRepository shipRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    @Override
    @Transactional(readOnly = true)
    public boolean reservationExistsById(Long reservationId) {
        return reservationRepository.existsById(reservationId);
    }

    @Override
    @Transactional
    public Optional<Reservation> confirmReservation(Long reservationId, BigDecimal calculatedFee, Route route) {
        return reservationRepository.findById(reservationId).map(entity -> {
            entity.confirmReservation(reservationId, calculatedFee, route);
            return reservationMapper.toDomain(entity);
        });
    }

    @Override
    @Transactional
    public Long createHoldReservation(ReserveBayCommand command) {
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
        ReservationEntity hold = new ReservationEntity(starport, bay, customer, ship, command);
        ReservationEntity saved = reservationRepository.save(hold);
        return saved.getId();
    }

    @Override
    public boolean starportExistsByCode(String code) {
        return starportRepository.existsByCode(code);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findAllStarportCodes() {
        return Set.copyOf(starportRepository.findAllCodes());
    }

    @Override
    @Transactional
    public void cancelHold(Long reservationId) {
        reservationRepository.findById(reservationId).ifPresent(entity -> {
            // Guard: only a still-pending HOLD is compensable. If it already CONFIRMED (a late success)
            // or CANCELLED, leave it alone — never cancel a confirmed reservation.
            if (entity.getStatus() == ReservationEntity.ReservationStatus.HOLD) {
                entity.cancel();
            }
        });
    }

    @Override
    @Transactional
    public int reapStaleHolds(Instant cutoff) {
        return reservationRepository.cancelStaleHolds(
                ReservationEntity.ReservationStatus.HOLD,
                ReservationEntity.ReservationStatus.CANCELLED,
                cutoff,
                Instant.now());
    }
}
