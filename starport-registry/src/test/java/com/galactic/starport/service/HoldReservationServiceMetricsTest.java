package com.galactic.starport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.galactic.starport.repository.CustomerEntity;
import com.galactic.starport.repository.CustomerRepository;
import com.galactic.starport.repository.DockingBayEntity;
import com.galactic.starport.repository.DockingBayRepository;
import com.galactic.starport.repository.ReservationEntity;
import com.galactic.starport.repository.ReservationRepository;
import com.galactic.starport.repository.ShipEntity;
import com.galactic.starport.repository.ShipRepository;
import com.galactic.starport.repository.StarportEntity;
import com.galactic.starport.repository.StarportRepository;
import com.galactic.starport.service.Customer;
import com.galactic.starport.service.CustomerNotFoundException;
import com.galactic.starport.service.DockingBay;
import com.galactic.starport.service.Ship;
import com.galactic.starport.service.Starport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HoldReservationServiceMetricsTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private ShipRepository shipRepository;
    @Mock private StarportRepository starportRepository;
    @Mock private DockingBayRepository dockingBayRepository;
    @Mock private ReservationRepository reservationRepository;

    private SimpleMeterRegistry meterRegistry;
    private HoldReservationService holdReservationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        holdReservationService = new HoldReservationService(
                customerRepository,
                shipRepository,
                starportRepository,
                dockingBayRepository,
                reservationRepository,
                meterRegistry);
        holdReservationService.initMetrics();
    }

    @Test
    void recordsSuccessfulHoldAllocation() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .build();

        StarportEntity starport = new StarportEntity();
        CustomerEntity customer = org.mockito.Mockito.mock(CustomerEntity.class);
        ShipEntity ship = org.mockito.Mockito.mock(ShipEntity.class);
        DockingBayEntity bay = org.mockito.Mockito.mock(DockingBayEntity.class);
        ReservationEntity saved = org.mockito.Mockito.mock(ReservationEntity.class);

        when(customerRepository.findByCustomerCode(command.customerCode()))
                .thenReturn(Optional.of(customer));
        when(shipRepository.findByShipCode(command.shipCode())).thenReturn(Optional.of(ship));
        when(dockingBayRepository.findFreeBay(
                        command.destinationStarportCode(),
                        command.shipClass().name(),
                        command.startAt(),
                        command.endAt()))
                .thenReturn(Optional.of(bay));
        when(reservationRepository.save(any(ReservationEntity.class))).thenReturn(saved);
        when(saved.getId()).thenReturn(1L);
        when(saved.getStartAt()).thenReturn(command.startAt());
        when(saved.getEndAt()).thenReturn(command.endAt());
        when(saved.getStatus()).thenReturn(ReservationEntity.ReservationStatus.HOLD);
        when(starport.toModel()).thenReturn(Starport.builder().build());
        when(bay.toModel()).thenReturn(DockingBay.builder().build());
        when(customer.toModel()).thenReturn(Customer.builder().build());
        when(ship.toModel()).thenReturn(Ship.builder().shipClass(Ship.ShipClass.SCOUT).build());
        when(ship.getShipCode()).thenReturn("SHIP");
        when(customer.getCustomerCode()).thenReturn("CUST");
        when(bay.getId()).thenReturn(5L);

        holdReservationService.allocateHold(command, starport);

        assertEquals(1.0, meterRegistry.get("reservations.hold.allocate.success").counter().count());
        assertEquals(1, meterRegistry.get("reservations.hold.allocate.duration").timer().count());
        assertEquals(0.0, meterRegistry.get("reservations.hold.allocate.errors").counter().count());
    }

    @Test
    void recordsErrorsWhenHoldAllocationFails() {
        ReserveBayCommand command = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .build();

        when(customerRepository.findByCustomerCode(command.customerCode())).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> holdReservationService.allocateHold(command, new StarportEntity()));

        assertEquals(1.0, meterRegistry.get("reservations.hold.allocate.errors").counter().count());
        assertEquals(0.0, meterRegistry.get("reservations.hold.allocate.success").counter().count());
        assertEquals(1, meterRegistry.get("reservations.hold.allocate.duration").timer().count());
    }
}
