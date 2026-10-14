package com.galactic.fleet.booking.service.validation;

import com.galactic.fleet.booking.service.CustomerNotFoundException;
import com.galactic.fleet.booking.service.InvalidReservationException;
import com.galactic.fleet.booking.service.InvalidReservationTimeException;
import com.galactic.fleet.booking.service.ReserveBayCommand;
import com.galactic.fleet.booking.service.ShipNotFoundException;
import com.galactic.fleet.booking.service.StarportNotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReserveBayValidator implements Validator {
    private final List<ReserveBayCommandValidationRule> validationRules;

    @Override
    public boolean supports(Class<?> clazz) {
        return ReserveBayCommand.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        ReserveBayCommand command = (ReserveBayCommand) target;
        for (ReserveBayCommandValidationRule rule : validationRules) {
            rule.validate(command, errors);
        }
        log.info("Reservation command validated. errorsCount={}", errors.getErrorCount());
    }

    public void validate(ReserveBayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(command, "reserveBayCommand");
        validate(command, errors);
        if (!errors.hasErrors()) {
            return;
        }
        Set<String> codes = errors.getAllErrors().stream()
                .flatMap(error -> Arrays.stream(error.getCodes()))
                .collect(Collectors.toSet());
        log.debug("Validation failed for ReserveBayCommand. codes={}", codes);
        if (codes.contains(ReservationTimeValidationRule.ERROR_CODE)) {
            throw new InvalidReservationTimeException(command.startAt(), command.endAt());
        }
        if (codes.contains(StartStarportValidationRule.ERROR_CODE)) {
            throw new StarportNotFoundException(command.startStarportCode());
        }
        if (codes.contains(DestinationStarportValidationRule.ERROR_CODE)) {
            throw new StarportNotFoundException(command.destinationStarportCode());
        }
        if (codes.contains(CustomerValidationRule.ERROR_CODE)) {
            throw new CustomerNotFoundException(command.customerCode());
        }
        if (codes.contains(ShipValidationRule.ERROR_CODE)) {
            throw new ShipNotFoundException(command.shipCode());
        }
        throw new InvalidReservationException("Unsupported validation error(s): " + String.join(", ", codes));
    }
}
