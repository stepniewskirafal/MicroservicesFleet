package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@Order(10)
@RequiredArgsConstructor
class StartStarportValidationRule implements ReserveBayCommandValidationRule {
    private final StarportPersistenceFacade persistenceFacade;

    @Override
    public void validate(ReserveBayCommand command) {
        if (command.requestRoute()) {
            final String starportCode = command.startStarportCode();
            if (!persistenceFacade.starportExistsByCode(starportCode)) {
                throw new StarportNotFoundException(starportCode);
            }
            log.debug("Starport {} exists.", starportCode);
        }
    }
}
