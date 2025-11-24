package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@Component
@Slf4j
@Order(20)
@RequiredArgsConstructor
class DestinationStarportValidationRule implements ReserveBayCommandValidationRule {
    static final String ERROR_CODE = "starport.destination.notFound";
    private final StarportPersistenceFacade persistenceFacade;

    @Override
    public void validate(ReserveBayCommand command, Errors errors) {
        final String destinationStarportCode = command.destinationStarportCode();
        if (!persistenceFacade.starportExistsByCode(destinationStarportCode)) {
            errors.reject(
                    ERROR_CODE,
                    new Object[] {destinationStarportCode},
                    "Starport not found: '%s'".formatted(destinationStarportCode));
            log.debug("Starport {} does not exist.", destinationStarportCode);
            return;
        }
        log.debug("Starport {} exists.", destinationStarportCode);
    }
}
