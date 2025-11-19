package com.galactic.starport.service;

import com.galactic.starport.repository.StarportPersistenceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@Order(20)
@RequiredArgsConstructor
class DestinationStarportValidationRule implements ReserveBayCommandValidationRule {
    private final StarportPersistenceFacade persistenceFacade;

    @Override
    public void validate(ReserveBayCommand command) {
        final String destinationStarportCode = command.destinationStarportCode();
        if (!persistenceFacade.starportExistsByCode(destinationStarportCode)) {
            throw new StarportNotFoundException(destinationStarportCode);
        }
        log.debug("Starport {} exists.", destinationStarportCode);
    }
}
