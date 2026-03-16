package com.galactic.starport.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.galactic.starport.repository.StarportPersistenceFacade;
import com.galactic.starport.service.ReserveBayCommand;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class StartStarportValidationRuleTest {

    @Mock
    private StarportPersistenceFacade persistenceFacade;

    private StartStarportValidationRule rule;

    @BeforeEach
    void setUp() {
        rule = new StartStarportValidationRule(persistenceFacade);
    }

    @Test
    void should_pass_when_route_requested_and_start_starport_exists() {
        ReserveBayCommand cmd = aCommand("ALPHA-BASE", true);
        Errors errors = errorsFor(cmd);
        given(persistenceFacade.starportExistsByCode("ALPHA-BASE")).willReturn(true);

        rule.validate(cmd, errors);

        assertThat(errors.hasErrors()).isFalse();
    }

    @Test
    void should_reject_when_route_requested_and_start_starport_does_not_exist() {

        ReserveBayCommand cmd = aCommand("MISSING", true);
        Errors errors = errorsFor(cmd);
        given(persistenceFacade.starportExistsByCode("MISSING")).willReturn(false);

        rule.validate(cmd, errors);

        assertThat(errors.hasErrors()).isTrue();
        assertThat(errors.getGlobalError().getCode()).isEqualTo(StartStarportValidationRule.ERROR_CODE);
    }

    @Test
    void should_skip_validation_when_route_not_requested() {

        ReserveBayCommand cmd = aCommand("ANYTHING", false);
        Errors errors = errorsFor(cmd);

        rule.validate(cmd, errors);

        assertThat(errors.hasErrors()).isFalse();
        then(persistenceFacade).should(never()).starportExistsByCode("ANYTHING");
    }

    @Test
    void should_reject_when_route_requested_and_start_starport_code_is_null() {

        ReserveBayCommand cmd = aCommand(null, true);
        Errors errors = errorsFor(cmd);

        rule.validate(cmd, errors);

        assertThat(errors.hasErrors()).isTrue();
        assertThat(errors.getGlobalError().getCode()).isEqualTo(StartStarportValidationRule.ERROR_CODE);
    }

    @Test
    void should_reject_when_route_requested_and_start_starport_code_is_blank() {

        ReserveBayCommand cmd = aCommand("   ", true);
        Errors errors = errorsFor(cmd);

        rule.validate(cmd, errors);

        assertThat(errors.hasErrors()).isTrue();
        assertThat(errors.getGlobalError().getCode()).isEqualTo(StartStarportValidationRule.ERROR_CODE);
    }

    private static ReserveBayCommand aCommand(String startCode, boolean requestRoute) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode(startCode)
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2027-01-01T08:00:00Z"))
                .endAt(Instant.parse("2027-01-01T09:00:00Z"))
                .requestRoute(requestRoute)
                .build();
    }

    private static Errors errorsFor(ReserveBayCommand cmd) {
        return new BeanPropertyBindingResult(cmd, "reserveBayCommand");
    }
}
