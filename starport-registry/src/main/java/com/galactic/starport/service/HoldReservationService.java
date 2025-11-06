package com.galactic.starport.service;

import com.galactic.starport.repository.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HoldReservationService {
    private final CustomerRepository customerRepository;
    private final ShipRepository shipRepository;
    private final StarportRepository starportRepository;
    private final DockingBayRepository dockingBayRepository;
    private final ReservationRepository reservationRepository;
    private final MeterRegistry meterRegistry;

    private Timer holdAllocationTimer;
    private Counter holdAllocationSuccessCounter;
    private Counter holdAllocationErrorCounter;

    @PostConstruct
    void initMetrics() {
        holdAllocationTimer = Timer.builder("reservations.hold.allocate.duration")
                .description("Time spent allocating reservation holds")
                .register(meterRegistry);
        holdAllocationSuccessCounter = Counter.builder("reservations.hold.allocate.success")
                .description("Number of holds allocated successfully")
                .register(meterRegistry);
        holdAllocationErrorCounter = Counter.builder("reservations.hold.allocate.errors")
                .description("Number of hold allocations ending with an error")
                .register(meterRegistry);
    }

    @Transactional
    public Reservation allocateHold(ReserveBayCommand command, StarportEntity starport) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
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
            log.info(
                    "HOLD created: reservationId={}, starport={}, bay={}, ship={}, customer={}, window=[{}..{}]",
                    saved.getId(),
                    starport.getCode(),
                    bay.getId(),
                    ship.getShipCode(),
                    customer.getCustomerCode(),
                    command.startAt(),
                    command.endAt());

            holdAllocationSuccessCounter.increment();

            return Reservation.builder()
                    .id(saved.getId())
                    .starport(starport.toModel())
                    .dockingBay(bay.toModel())
                    .customer(customer.toModel())
                    .ship(ship.toModel())
                    .startAt(saved.getStartAt())
                    .endAt(saved.getEndAt())
                    .status(Reservation.ReservationStatus.valueOf(saved.getStatus().name()))
                    .build();
        } catch (RuntimeException ex) {
            holdAllocationErrorCounter.increment();
            throw ex;
        } finally {
            sample.stop(holdAllocationTimer);
        }
    }
}
