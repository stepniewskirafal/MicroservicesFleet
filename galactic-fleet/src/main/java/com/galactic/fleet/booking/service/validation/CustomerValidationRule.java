package com.galactic.fleet.booking.service.validation;

import com.galactic.fleet.booking.repository.StarportPersistenceFacade;
import com.galactic.fleet.booking.service.ReserveBayCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

/**
 * The customer must be known. Checked here — before the fee and the route are computed — so a
 * request that is going to be refused with 404 never plans a route (ADR-0043).
 */
@Component
@Order(30)
@RequiredArgsConstructor
class CustomerValidationRule implements ReserveBayCommandValidationRule {
    static final String ERROR_CODE = "customer.notFound";
    private final StarportPersistenceFacade persistenceFacade;

    @Override
    public void validate(ReserveBayCommand command, Errors errors) {
        if (!persistenceFacade.customerExistsByCode(command.customerCode())) {
            errors.reject(
                    ERROR_CODE,
                    new Object[] {command.customerCode()},
                    "Customer not found: '%s'".formatted(command.customerCode()));
        }
    }
}
