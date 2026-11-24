package com.galactic.fleet.booking.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.galactic.fleet.booking.repository.StarportPersistenceFacade;
import com.galactic.fleet.booking.service.CustomerNotFoundException;
import com.galactic.fleet.booking.service.ReserveBayCommand;
import com.galactic.fleet.booking.service.ShipNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

/**
 * Customer and ship are checked during validation — before the fee and the route are computed — so
 * a request that ends in 404 never plans a route (ADR-0043).
 */
class CustomerAndShipValidationRuleTest {

    private StarportPersistenceFacade persistenceFacade;
    private ReserveBayValidator validator;

    @BeforeEach
    void setUp() {
        persistenceFacade = mock(StarportPersistenceFacade.class);
        validator = new ReserveBayValidator(
                List.of(new CustomerValidationRule(persistenceFacade), new ShipValidationRule(persistenceFacade)));
        given(persistenceFacade.customerExistsByCode(any())).willReturn(true);
        given(persistenceFacade.shipExistsByCode(any())).willReturn(true);
    }

    @Test
    void a_known_customer_and_ship_pass() {
        validator.validate(aCommand());
    }

    @Test
    void an_unknown_customer_is_rejected_as_customer_not_found() {
        given(persistenceFacade.customerExistsByCode("CUST-001")).willReturn(false);

        assertThatThrownBy(() -> validator.validate(aCommand()))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining("CUST-001");
    }

    @Test
    void an_unknown_ship_is_rejected_as_ship_not_found() {
        given(persistenceFacade.shipExistsByCode("SS-001")).willReturn(false);

        assertThatThrownBy(() -> validator.validate(aCommand()))
                .isInstanceOf(ShipNotFoundException.class)
                .hasMessageContaining("SS-001");
    }

    @Test
    void an_unknown_customer_wins_over_an_unknown_ship() {
        // The same precedence the persistence lookups had before the check moved here.
        given(persistenceFacade.customerExistsByCode(any())).willReturn(false);
        given(persistenceFacade.shipExistsByCode(any())).willReturn(false);

        assertThatThrownBy(() -> validator.validate(aCommand())).isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void each_rule_reports_its_own_error_code() {
        given(persistenceFacade.customerExistsByCode(any())).willReturn(false);
        given(persistenceFacade.shipExistsByCode(any())).willReturn(false);
        ReserveBayCommand command = aCommand();
        Errors errors = new BeanPropertyBindingResult(command, "reserveBayCommand");

        new CustomerValidationRule(persistenceFacade).validate(command, errors);
        new ShipValidationRule(persistenceFacade).validate(command, errors);

        assertThat(errors.getAllErrors())
                .extracting(error -> error.getCode())
                .containsExactly(CustomerValidationRule.ERROR_CODE, ShipValidationRule.ERROR_CODE);
    }

    private static ReserveBayCommand aCommand() {
        return ReserveBayCommand.builder()
                .startStarportCode("ALPHA-BASE")
                .destinationStarportCode("DEF")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2030-01-01T00:00:00Z"))
                .endAt(Instant.parse("2030-01-01T01:00:00Z"))
                .requestRoute(false)
                .build();
    }
}
