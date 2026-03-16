package com.galactic.starport.service.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.galactic.starport.BaseAcceptanceTest;
import com.galactic.starport.service.InvalidReservationTimeException;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.StarportNotFoundException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
class ReserveBayValidationServiceRepositoryTest extends BaseAcceptanceTest {

    @Autowired
    ReserveBayValidator validator;

    @Test
    void validCommandPassesWhenAllPreconditionsAreMet() {
        String originCode = "ALPHA-BASE-VALID";
        String destinationCode = "DEF-VALID";
        String customerCode = "CUST-VALID";
        String shipCode = "SS-Enterprise-VALID";

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
                .startAt(Instant.parse("2006-01-01T00:00:00Z"))
                .endAt(Instant.parse("2006-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        assertDoesNotThrow(() -> validator.validate(cmd));
    }

    @Test
    void rejectsWhenDestinationStarportDoesNotExist() {
        String originCode = "ALPHA-BASE-DEST-NF";
        String existingDestinationCode = "DEF-DEST-NF-EXISTS";
        String missingDestinationCode = "DEF-DEST-NF-MISSING";
        String customerCode = "CUST-DEST-NF";
        String shipCode = "SS-Enterprise-DEST-NF";

        seedDefaultReservationFixture(
                existingDestinationCode,
                Map.of("originCode", originCode, "customerCode", customerCode, "shipCode", shipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(missingDestinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2027-02-09T08:00:00Z"))
                .endAt(Instant.parse("2027-02-09T09:00:00Z"))
                .requestRoute(true)
                .build();

        assertThrows(StarportNotFoundException.class, () -> validator.validate(cmd));
    }

    @Test
    void rejectsWhenRequestRouteTrueAndStartStarportDoesNotExist() {
        String existingOriginCode = "ALPHA-BASE-ROUTE-EXISTS";
        String missingStartCode = "ALPHA-BASE-ROUTE-MISSING";
        String destinationCode = "DEF-ROUTE-STARTMISSING";
        String customerCode = "CUST-ROUTE-STARTMISSING";
        String shipCode = "SS-Enterprise-ROUTE-STARTMISSING";

        seedDefaultReservationFixture(
                destinationCode,
                Map.of("originCode", existingOriginCode, "customerCode", customerCode, "shipCode", shipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(missingStartCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2027-02-09T08:00:00Z"))
                .endAt(Instant.parse("2027-02-09T09:00:00Z"))
                .requestRoute(true)
                .build();

        assertThrows(StarportNotFoundException.class, () -> validator.validate(cmd));
    }

    @Test
    void doesNotCheckStartStarportWhenRequestRouteFalse() {
        String originCode = "ALPHA-BASE-ROUTE-FALSE";
        String destinationCode = "DEF-ROUTE-FALSE";
        String customerCode = "CUST-ROUTE-FALSE";
        String shipCode = "SS-Enterprise-ROUTE-FALSE";

        seedDefaultReservationFixture(
                destinationCode, Map.of("originCode", originCode, "customerCode", customerCode, "shipCode", shipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode("NO-SUCH-START-ROUTE-FALSE")
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2027-02-09T08:00:00Z"))
                .endAt(Instant.parse("2027-02-09T09:00:00Z"))
                .requestRoute(false)
                .build();

        assertDoesNotThrow(() -> validator.validate(cmd));
    }

    static Stream<Arguments> invalidTimeRanges() {
        return Stream.of(
                Arguments.of(Instant.parse("2027-02-09T08:00:00Z"), Instant.parse("2027-02-09T08:00:00Z"), "EQ"),
                Arguments.of(Instant.parse("2027-02-09T10:00:00Z"), Instant.parse("2027-02-09T09:00:00Z"), "GT"));
    }

    @ParameterizedTest(name = "rejects invalid time range [{2}]: startAt={0}, endAt={1}")
    @MethodSource("invalidTimeRanges")
    void rejectsInvalidTimeRanges(Instant startAt, Instant endAt, String suffix) {

        String originCode = "ALPHA-BASE-TIME-" + suffix;
        String destinationCode = "DEF-TIME-" + suffix;
        String customerCode = "CUST-TIME-" + suffix;
        String shipCode = "SS-Enterprise-TIME-" + suffix;

        seedDefaultReservationFixture(
                destinationCode, Map.of("originCode", originCode, "customerCode", customerCode, "shipCode", shipCode));

        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode(originCode)
                .customerCode(customerCode)
                .shipCode(shipCode)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(startAt)
                .endAt(endAt)
                .requestRoute(true)
                .build();

        assertThrows(InvalidReservationTimeException.class, () -> validator.validate(cmd));
    }
}
