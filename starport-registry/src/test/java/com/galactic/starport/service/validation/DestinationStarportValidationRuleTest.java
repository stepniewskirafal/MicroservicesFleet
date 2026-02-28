package com.galactic.starport.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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
class DestinationStarportValidationRuleTest {

    @Mock
    private StarportPersistenceFacade persistenceFacade;

    private DestinationStarportValidationRule rule;

    @BeforeEach
    void setUp() {
        rule = new DestinationStarportValidationRule(persistenceFacade);
    }

    @Test
    void should_pass_when_destination_starport_exists() {
        // given
        ReserveBayCommand cmd = aCommand("DEF");
        Errors errors = errorsFor(cmd);
        given(persistenceFacade.starportExistsByCode("DEF")).willReturn(true);

        // when
        rule.validate(cmd, errors);

        // then
        assertThat(errors.hasErrors()).isFalse();
    }

    @Test
    void should_reject_when_destination_starport_does_not_exist() {
        // given
        ReserveBayCommand cmd = aCommand("MISSING");
        Errors errors = errorsFor(cmd);
        given(persistenceFacade.starportExistsByCode("MISSING")).willReturn(false);

        // when
        rule.validate(cmd, errors);

        // then
        assertThat(errors.hasErrors()).isTrue();
        assertThat(errors.getGlobalError().getCode()).isEqualTo(DestinationStarportValidationRule.ERROR_CODE);
    }

    private static ReserveBayCommand aCommand(String destinationCode) {
        return ReserveBayCommand.builder()
                .destinationStarportCode(destinationCode)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-001")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2027-01-01T08:00:00Z"))
                .endAt(Instant.parse("2027-01-01T09:00:00Z"))
                .requestRoute(true)
                .build();
    }

    private static Errors errorsFor(ReserveBayCommand cmd) {
        return new BeanPropertyBindingResult(cmd, "reserveBayCommand");
    }
}
