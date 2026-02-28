package com.galactic.starport.service.reservationcalculation;

import com.galactic.starport.service.Route;
import java.math.BigDecimal;

public record ReservationCalculation(Long reservationId, BigDecimal calculatedFee, Route route) {}
