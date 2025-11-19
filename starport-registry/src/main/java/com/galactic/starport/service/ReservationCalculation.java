package com.galactic.starport.service;

import java.math.BigDecimal;
import java.util.Optional;

record ReservationCalculation(Long reservationId, BigDecimal calculatedFee, Optional<Route> route) {}
