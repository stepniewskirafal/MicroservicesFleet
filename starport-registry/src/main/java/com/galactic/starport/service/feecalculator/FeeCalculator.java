package com.galactic.starport.service.feecalculator;

import com.galactic.starport.service.ReserveBayCommand;
import java.math.BigDecimal;

// Public: Single access point for fee calculation functionality
public interface FeeCalculator {
    BigDecimal calculateFee(ReserveBayCommand command);
}
