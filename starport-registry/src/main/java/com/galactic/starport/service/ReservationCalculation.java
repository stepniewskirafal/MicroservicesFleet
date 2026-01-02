package com.galactic.starport.service;

import java.math.BigDecimal;

record ReservationCalculation(Long reservationId, BigDecimal calculatedFee, Route route) {}
