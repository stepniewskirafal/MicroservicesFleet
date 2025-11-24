package com.galactic.starport.service;

import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

interface ReserveBayCommandValidationRule extends Validator {
    @Override
    default boolean supports(Class<?> clazz) {
        return ReserveBayCommand.class.isAssignableFrom(clazz);
    }

    @Override
    default void validate(Object target, Errors errors) {
        validate((ReserveBayCommand) target, errors);
    }

    void validate(ReserveBayCommand command, Errors errors);
}
