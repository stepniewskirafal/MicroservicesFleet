package com.galactic.starport.repository;

import com.galactic.starport.service.*;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class StarportPersistenceFacadeImpl implements StarportPersistenceFacade {
    private final CustomerRepository customerRepository;
    private final StarportRepository starportRepository;
    private final DockingBayRepository dockingBayRepository;
    private final ShipRepository shipRepository;
    private final ReservationRepository reservationRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean reservationExistsById(Long reservationId) {
        return reservationRepository.existsById(reservationId);
    }

    @Override
    public Long confirmReservation(Long reservationId, BigDecimal calculatedFee, Optional<Route> route) {
        return null;
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

    /*@Override
    public Optional<Customer> customerFindByCustomerCode(String customerCode) {
        return customerRepository
                .findByCustomerCode(customerCode)
                .map(CustomerMapper::toCustomer);
    }


    @Override
    public Optional<Starport> starportfindByCode(String code) {
        return starportRepository
                .findByCode(code)
                .map(StarportMapper::toStarport);
    }

    @Override
    Optional<DockingBay> dockingBayFindFreeBay(
            String starportCode,
            String shipClass,
            Instant startAt,
            Instant endAt){
        return dockingBayRepository.findFreeBay(
                starportCode,
                shipClass,
                startAt,
                endAt)
                .map(DockingBayMapper::toDockingBay);
    }

    @Override
    public Optional<Ship> shipFindByShipCode(String shipCode) {
        return shipRepository
                .findByShipCode(shipCode)
                .map(ShipMapper::toShip);
    }

    @Override
    public Optional<Ship> shipRequireByShipCode(String shipCode) {
        return shipRepository
                .findByShipCode(shipCode)
                .map(ShipMapper::toShip);
    }*/

}
