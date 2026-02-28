package com.galactic.starport.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.starport.service.ReserveBayCommand;
import java.time.Instant;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

class ReservationTimeValidationProperties {

    private final ReservationTimeValidationRule rule = new ReservationTimeValidationRule();

    @Property
    void should_accept_all_valid_time_ranges_where_start_is_before_end(
            @ForAll @LongRange(min = 1, max = 365 * 24) long hoursAhead) {
        // given
        Instant start = Instant.parse("2027-01-01T00:00:00Z");
        Instant end = start.plusSeconds(hoursAhead * 3600L);
        ReserveBayCommand cmd = aCommand(start, end);
        Errors errors = errorsFor(cmd);

        // when
        rule.validate(cmd, errors);

        // then
        assertThat(errors.hasErrors()).isFalse();
    }

    @Property
    void should_reject_all_time_ranges_where_end_is_before_start(
            @ForAll @LongRange(min = 1, max = 365 * 24) long hoursBefore) {
        // given
        Instant start = Instant.parse("2027-06-01T00:00:00Z");
        Instant end = start.minusSeconds(hoursBefore * 3600L);
        ReserveBayCommand cmd = aCommand(start, end);
        Errors errors = errorsFor(cmd);

        // when
        rule.validate(cmd, errors);

        // then
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
