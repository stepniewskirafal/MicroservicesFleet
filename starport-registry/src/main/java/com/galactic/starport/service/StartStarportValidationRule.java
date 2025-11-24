package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@Component
@Slf4j
@Order(10)
@RequiredArgsConstructor
class StartStarportValidationRule implements ReserveBayCommandValidationRule {
    static final String ERROR_CODE = "starport.start.notFound";
    private final StarportPersistenceFacade persistenceFacade;

    @Override
    public void validate(ReserveBayCommand command, Errors errors) {
        if (!command.requestRoute()) {
            return;
        }
        final String starportCode = command.startStarportCode();
        if (starportCode == null || starportCode.isBlank()) {
            errors.reject(ERROR_CODE, new Object[] {starportCode}, "Start starport code must be provided.");
            log.debug("Start starport code missing.");
            return;
        }
        if (!persistenceFacade.starportExistsByCode(starportCode)) {
            errors.reject(ERROR_CODE, new Object[] {starportCode}, "Starport not found: '%s'".formatted(starportCode));
            log.debug("Starport {} does not exist.", starportCode);
            return;
        }
        log.debug("Starport {} exists.", starportCode);
    }
}
