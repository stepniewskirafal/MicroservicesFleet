package com.galactic.starport.application.service.tariff;

import com.galactic.starport.domain.enums.ShipClass;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class FlatTariffPolicy implements TariffPolicy {
    @Override
    public BigDecimal calculate(ShipClass shipClass, long hours) {
        if (hours <= 0) hours = 1;
        BigDecimal perHour =
                switch (shipClass) {
                    case SCOUT -> BigDecimal.valueOf(50);
                    case FREIGHTER -> BigDecimal.valueOf(120);
                    case CRUISER -> BigDecimal.valueOf(250);
                    case UNKNOWN -> BigDecimal.valueOf(1000);
                };
        return perHour.multiply(BigDecimal.valueOf(hours));
    }
}
