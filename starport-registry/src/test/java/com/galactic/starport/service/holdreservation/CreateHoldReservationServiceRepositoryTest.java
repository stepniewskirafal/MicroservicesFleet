package com.galactic.starport.service.holdreservation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.galactic.starport.BaseAcceptanceTest;
import com.galactic.starport.repository.StarportPersistenceFacade;
import com.galactic.starport.service.CustomerNotFoundException;
import com.galactic.starport.service.NoDockingBaysAvailableException;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.ShipNotFoundException;
import com.galactic.starport.service.StarportNotFoundException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
class CreateHoldReservationServiceRepositoryTest extends BaseAcceptanceTest {

    @Autowired
    HoldReservationFacade holdReservationFacade;

    @Autowired
    StarportPersistenceFacade starportPersistenceFacade;

    @Test
    void shouldAllocateReservation() {
        String originCode = "ALPHA-BASE-ALLOCATE";
        String destinationCode = "DEF-ALLOCATE";
        String customerCode = "CUST-ALLOCATE";
        String shipCode = "SS-Enterprise-ALLOCATE";

        seedDefaultReservationFixture(
                destinationCode,
                Map.of(
                        "originCode", originCode,
                        "customerCode", customerCode,
                        "shipCode", shipCode,
                        "destinationName", "Alpha Base Central"));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        Long reservationID = holdReservationFacade.createHoldReservation(cmd);

        assertTrue(starportPersistenceFacade.reservationExistsById(reservationID));
    }

    @Test
    void allocateHoldThrowsStarportNotFoundExceptionWhenStarportDoesNotExist() {
        String originCode = "ALPHA-BASE-STARPORT-NF";
        String existingDestinationCode = "DEF-STARPORT-NF-EXISTS";
        String missingDestinationCode = "DEF-STARPORT-NF-MISSING";
        String customerCode = "CUST-STARPORT-NF";
        String shipCode = "SS-Enterprise-STARPORT-NF";

        seedDefaultReservationFixture(
                existingDestinationCode,
                Map.of("originCode", originCode, "customerCode", customerCode, "shipCode", shipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(missingDestinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        assertThrows(StarportNotFoundException.class, () -> holdReservationFacade.createHoldReservation(cmd));
    }

    @Test
    void allocateHoldThrowsCustomerNotFoundExceptionWhenCustomerDoesNotExist() {
        String originCode = "ALPHA-BASE-CUSTOMER-NF";
        String destinationCode = "DEF-CUSTOMER-NF";
        String existingCustomerCode = "CUST-CUSTOMER-NF-EXISTS";
        String missingCustomerCode = "CUST-CUSTOMER-NF-MISSING";
        String shipCode = "SS-Enterprise-CUSTOMER-NF";

        seedDefaultReservationFixture(
                destinationCode,
                Map.of("originCode", originCode, "customerCode", existingCustomerCode, "shipCode", shipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(missingCustomerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        assertThrows(CustomerNotFoundException.class, () -> holdReservationFacade.createHoldReservation(cmd));
    }

    @Test
    void allocateHoldThrowsShipNotFoundExceptionWhenShipDoesNotExist() {
        String originCode = "ALPHA-BASE-SHIP-NF";
        String destinationCode = "DEF-SHIP-NF";
        String customerCode = "CUST-SHIP-NF";
        String existingShipCode = "SS-Enterprise-SHIP-NF-EXISTS";
        String missingShipCode = "SS-Enterprise-SHIP-NF-MISSING";

        seedDefaultReservationFixture(
                destinationCode,
                Map.of("originCode", originCode, "customerCode", customerCode, "shipCode", existingShipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(missingShipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        assertThrows(ShipNotFoundException.class, () -> holdReservationFacade.createHoldReservation(cmd));
    }

    @Test
    void allocateHoldThrowsNoDockingBaysAvailableExceptionWhenNoBayAvailable() {
        String originCode = "ALPHA-BASE-NO-BAY";
        String destinationCode = "DEF-NO-BAY";
        String customerCode = "CUST-NO-BAY";
        String shipCode = "SS-Enterprise-NO-BAY";

        seedDefaultReservationFixture(
                destinationCode, Map.of("originCode", originCode, "customerCode", customerCode, "shipCode", shipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2002-01-01T00:00:00Z"))
                .endAt(Instant.parse("2002-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        holdReservationFacade.createHoldReservation(cmd);

        assertThrows(NoDockingBaysAvailableException.class, () -> holdReservationFacade.createHoldReservation(cmd));
    }
}
