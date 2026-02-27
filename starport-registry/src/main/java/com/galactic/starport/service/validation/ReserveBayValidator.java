package com.galactic.starport.service.validation;

import com.galactic.starport.service.ReserveBayCommand;

public interface ReserveBayValidator {
    void validate(ReserveBayCommand command);
}
