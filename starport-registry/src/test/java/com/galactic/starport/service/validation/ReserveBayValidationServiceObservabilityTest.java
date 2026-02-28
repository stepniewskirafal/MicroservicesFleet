package com.galactic.starport.service.validation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.validation.Errors;

@Execution(ExecutionMode.CONCURRENT)
class ReserveBayValidationServiceObservabilityTest {

    private TestObservationRegistry observationRegistry;
    private ReserveBayCommandValidationRule rule1;
    private ReserveBayCommandValidationRule rule2;
    private ReserveBayValidationService composite;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        rule1 = mock(ReserveBayCommandValidationRule.class);
        rule2 = mock(ReserveBayCommandValidationRule.class);
        composite = new ReserveBayValidationService(List.of(rule1, rule2), observationRegistry);
    }

    @Test
    void createsParentObservationAndChildObservationsPerRuleWithProperTags() {
        // given - poprawna komenda
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .startStarportCode("START")
                .customerCode("CUST-001")
                .shipCode("SHIP-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2005-01-01T00:00:00Z"))
                .endAt(Instant.parse("2005-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();

        // when
        composite.validate(cmd);

        // then - każda reguła została wywołana dokładnie raz
        verify(rule1).validate(eq(cmd), any(Errors.class));
        verify(rule2).validate(eq(cmd), any(Errors.class));

        // and - parent observation ma poprawną nazwę, tag i został rozpoczęty/zatrzymany
        TestObservationRegistryAssert obsAssert =
                TestObservationRegistryAssert.assertThat(observationRegistry);
        obsAssert
                .hasObservationWithNameEqualTo("validation.reserve-bay")
                .that()
                .hasLowCardinalityKeyValue("routeRequested", "true")
                .hasBeenStarted()
                .hasBeenStopped();

        // and - dla każdej reguły utworzono observation 'validation.rule' z nazwą reguły
        obsAssert.hasNumberOfObservationsWithNameEqualTo("validation.rule", 2);

        obsAssert
                .hasObservationWithNameEqualTo("validation.rule")
                .that()
                .hasLowCardinalityKeyValue("rule", rule1.getClass().getSimpleName());

        obsAssert
                .hasObservationWithNameEqualTo("validation.rule")
                .that()
                .hasLowCardinalityKeyValue("rule", rule2.getClass().getSimpleName());
    }

    @Test
    void marksParentObservationAsErrorWhenRuleThrowsAndStillStopsIt() {
        // given
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .startStarportCode("START")
                .customerCode("CUST-001")
                .shipCode("SHIP-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2005-01-01T00:00:00Z"))
                .endAt(Instant.parse("2005-01-01T01:00:00Z"))
                .requestRoute(false)
                .build();

        // and - pierwsza reguła rzuca wyjątek
        RuntimeException boom = new RuntimeException("boom");
        doThrow(boom).when(rule1).validate(eq(cmd), any(Errors.class));

        // when / then - wyjątek propaguje się do testu
        RuntimeException thrownEx = assertThrows(RuntimeException.class, () -> composite.validate(cmd));
        assertSame(boom, thrownEx);

        // and - parent observation ma oznaczony błąd i został zatrzymany
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("validation.reserve-bay")
                .that()
                .hasError()
                .hasBeenStopped();
    }
}
