package com.galactic.starport.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Arrays;
import java.util.List;
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
class ReserveBayValidationService implements Validator {
    private final List<ReserveBayCommandValidationRule> validationRules;
    private final ObservationRegistry observationRegistry;
    private static final String OBSERVATION_NAME = "validation.reserve-bay";

    @Override
    public boolean supports(Class<?> clazz) {
        return ReserveBayCommand.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        ReserveBayCommand command = (ReserveBayCommand) target;
        Observation parent = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("routeRequested", String.valueOf(command.requestRoute()));
        parent.observe(() -> {
            for (ReserveBayCommandValidationRule rule : validationRules) {
                Observation.createNotStarted("validation.rule", observationRegistry)
                        .parentObservation(parent)
                        .lowCardinalityKeyValue("rule", rule.getClass().getSimpleName())
                        .observe(() -> rule.validate(command, errors));
            }
        });
        log.info("Reservation command validated. errorsCount={}", errors.getErrorCount());
    }

    public void validate(ReserveBayCommand command) {
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
        throw new InvalidReservationException("Unsupported validation error(s): " + String.join(", ", codes));
    }
}
