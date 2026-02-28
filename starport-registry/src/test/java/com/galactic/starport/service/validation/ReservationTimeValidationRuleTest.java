package com.galactic.starport.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.starport.service.ReserveBayCommand;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

@Execution(ExecutionMode.CONCURRENT)
class ReservationTimeValidationRuleTest {

    private ReservationTimeValidationRule rule;

    @BeforeEach
    void setUp() {
        rule = new ReservationTimeValidationRule();
    }

    @Test
    void should_pass_when_start_is_before_end() {
        
        ReserveBayCommand cmd = aCommand(
                Instant.parse("2027-01-01T08:00:00Z"), Instant.parse("2027-01-01T09:00:00Z"));
        Errors errors = errorsFor(cmd);

        
        rule.validate(cmd, errors);

        
        assertThat(errors.hasErrors()).isFalse();
    }

    @Test
    void should_reject_when_start_equals_end() {
        
        Instant same = Instant.parse("2027-01-01T08:00:00Z");
        ReserveBayCommand cmd = aCommand(same, same);
        Errors errors = errorsFor(cmd);

        
        rule.validate(cmd, errors);

        
        assertThat(errors.hasErrors()).isTrue();
        assertThat(errors.getGlobalError().getCode()).isEqualTo(ReservationTimeValidationRule.ERROR_CODE);
    }

    @Test
    void should_reject_when_start_is_after_end() {
        
        ReserveBayCommand cmd = aCommand(
                Instant.parse("2027-01-01T10:00:00Z"), Instant.parse("2027-01-01T09:00:00Z"));
        Errors errors = errorsFor(cmd);

        
        rule.validate(cmd, errors);

        
        assertThat(errors.hasErrors()).isTrue();
        assertThat(errors.getGlobalError().getCode()).isEqualTo(ReservationTimeValidationRule.ERROR_CODE);
    }

    @Test
    void should_reject_when_start_is_null() {
        
        ReserveBayCommand cmd = aCommand(null, Instant.parse("2027-01-01T09:00:00Z"));
        Errors errors = errorsFor(cmd);

        
        rule.validate(cmd, errors);

        
        assertThat(errors.hasErrors()).isTrue();
    }

    @Test
    void should_reject_when_end_is_null() {
        
        ReserveBayCommand cmd = aCommand(Instant.parse("2027-01-01T08:00:00Z"), null);
        Errors errors = errorsFor(cmd);

        
        rule.validate(cmd, errors);

        
        assertThat(errors.hasErrors()).isTrue();
    }

    private static ReserveBayCommand aCommand(Instant start, Instant end) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(start)
                .endAt(end)
                .requestRoute(true)
                .build();
    }

    private static Errors errorsFor(ReserveBayCommand cmd) {
        return new BeanPropertyBindingResult(cmd, "reserveBayCommand");
    }
}
