package com.galactic.starport.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
class ReserveBayValidationComposite {
    private final List<ReserveBayCommandValidationRule> validationRules;
    private final ObservationRegistry observationRegistry;

    void validate(ReserveBayCommand command) {
        Observation parent = Observation.createNotStarted("validation.reserve-bay", observationRegistry)
                .lowCardinalityKeyValue("routeRequested", String.valueOf(command.requestRoute()));

        parent.observe(() -> {
            for (ReserveBayCommandValidationRule rule : validationRules) {
                Observation.createNotStarted("validation.rule", observationRegistry)
                        .parentObservation(parent)
                        .lowCardinalityKeyValue("rule", rule.getClass().getSimpleName())
                        .observe(() -> rule.validate(command));
            }
        });
        log.info("Reservation command validated successfully");
    }
}
