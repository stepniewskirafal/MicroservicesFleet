package com.galactic.starport.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.galactic.starport.repository.StarportPersistenceFacade;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class CreateHoldReservationServiceObservabilityTest {

    private static final String DEST = "DEF";

    private TestObservationRegistry observationRegistry;
    private StarportPersistenceFacade persistenceFacade;
    private CreateHoldReservationService service;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        persistenceFacade = mock(StarportPersistenceFacade.class);
        service = new CreateHoldReservationService(persistenceFacade, observationRegistry);
    }

    @Test
    void allocateHoldCreatesObservationAndDelegatesToPersistenceFacade() {
        // given
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2001-01-01T00:00:00Z"))
                .endAt(Instant.parse("2001-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        // when
        service.createHoldReservation(cmd);

        // then - serwis deleguje do fasady
        verify(persistenceFacade).createHoldReservation(cmd);

        // and - obserwacja została zarejestrowana z poprawnymi tagami
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.hold.allocate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("starport", DEST)
                .hasLowCardinalityKeyValue("shipClass", cmd.shipClass().name());
    }
}
