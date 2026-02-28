package com.galactic.starport.service.feecalculator;

import com.galactic.starport.service.ReserveBayCommand;
import java.math.BigDecimal;

public interface FeeCalculator {
    BigDecimal calculateFee(ReserveBayCommand command);
}
