package com.galactic.starport.application.service.tariff;

import com.galactic.starport.domain.enums.ShipClass;
import java.math.BigDecimal;

public interface TariffPolicy {
    BigDecimal calculate(ShipClass shipClass, long durationHours);
}
