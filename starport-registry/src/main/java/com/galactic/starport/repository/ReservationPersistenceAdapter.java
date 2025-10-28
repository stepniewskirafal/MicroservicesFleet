package com.galactic.starport.repository;

import com.galactic.starport.domain.Customer;
import com.galactic.starport.domain.DockingBay;
import com.galactic.starport.domain.Reservation;
import com.galactic.starport.domain.Ship;
import com.galactic.starport.repository.mapper.CustomerMapper;
import com.galactic.starport.repository.mapper.ReservationMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationPersistenceAdapter {

    private final ReservationRepository reservationRepository;
    private final DockingBayRepository dockingBayRepository;
    private final CustomerRepository customerRepository;
    private final ShipRepository shipRepository;

    public Reservation save(Reservation reservation) {
        DockingBay dockingBay = reservation.getDockingBay();
        Customer customer = reservation.getCustomer();
        Ship ship = reservation.getShip();

        DockingBayEntity dockingBayEntity = dockingBayRepository
                .findById(dockingBay.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("Docking bay not found for id " + dockingBay.getId()));

        CustomerEntity customerEntity = customerRepository
                .findByCustomerCode(customer.getCustomerCode())
                .orElseThrow(() ->
                        new IllegalArgumentException("Customer not found for code " + customer.getCustomerCode()));

        ShipEntity shipEntity = shipRepository
                .findByShipCode(ship.getShipCode())
                .orElseThrow(() ->
                        new IllegalArgumentException("Ship not found for code " + ship.getShipCode()));

        ReservationEntity entity = reservation.getId() != null
                ? reservationRepository.findById(reservation.getId()).orElse(new ReservationEntity())
                : new ReservationEntity();

        ReservationMapper.updateEntity(reservation, dockingBayEntity, customerEntity, shipEntity, entity);
        ReservationEntity saved = reservationRepository.save(entity);
        return ReservationMapper.toDomain(saved);
    }

    public Optional<Reservation> findById(Long id) {
        return reservationRepository.findById(id).map(ReservationMapper::toDomain);
    }

    public Customer loadCustomerByCode(String customerCode) {
        return customerRepository
                .findByCustomerCode(customerCode)
                .map(CustomerMapper::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found for code " + customerCode));
    }

    public Ship loadShipByCode(String shipCode, Customer customer) {
        return shipRepository
                .findDomainByShipCode(shipCode, customer)
                .orElseThrow(() -> new IllegalArgumentException("Ship not found for code " + shipCode));
    }

    public void cancelReservation(Long reservationId) {
        reservationRepository
                .findById(reservationId)
                .ifPresent(entity -> {
                    entity.cancelRevervation();
                    reservationRepository.save(entity);
                });
    }
}
