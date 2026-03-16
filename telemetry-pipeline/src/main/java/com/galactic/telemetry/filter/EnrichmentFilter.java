package com.galactic.telemetry.filter;

import com.galactic.telemetry.config.SensorThresholdProperties;
import com.galactic.telemetry.config.SensorThresholdProperties.ThresholdRange;
import com.galactic.telemetry.model.EnrichedTelemetry;
import com.galactic.telemetry.model.ValidatedTelemetry;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnrichmentFilter implements Function<ValidatedTelemetry, EnrichedTelemetry> {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentFilter.class);

    private final SensorThresholdProperties thresholdProperties;

    // Static ship registry — in production this would come from Starport Registry via HTTP or cache
    private static final Map<String, ShipInfo> SHIP_REGISTRY = Map.of(
            "SHIP-001", new ShipInfo("Corvette", "Alpha-Centauri"),
            "SHIP-002", new ShipInfo("Freighter", "Kepler-442"),
            "SHIP-003", new ShipInfo("Cruiser", "Proxima-B"),
            "SHIP-004", new ShipInfo("Explorer", "Trappist-1e"),
            "SHIP-005", new ShipInfo("Dreadnought", "Sirius-Prime"));

    private static final ShipInfo DEFAULT_SHIP_INFO = new ShipInfo("Unknown", "Unknown");

    public EnrichmentFilter(SensorThresholdProperties thresholdProperties) {
        this.thresholdProperties = thresholdProperties;
    }

    @Override
    public EnrichedTelemetry apply(ValidatedTelemetry validated) {
        ShipInfo shipInfo = SHIP_REGISTRY.getOrDefault(validated.shipId(), DEFAULT_SHIP_INFO);
        ThresholdRange range = thresholdProperties.rangeFor(validated.sensorType());

        log.debug(
                "Enriching telemetry for ship {} (class={}, sector={}), sensor={}",
                validated.shipId(),
                shipInfo.shipClass(),
                shipInfo.sector(),
                validated.sensorType());

        return new EnrichedTelemetry(
                validated.shipId(),
                shipInfo.shipClass(),
                shipInfo.sector(),
                validated.sensorType(),
                validated.value(),
                validated.timestamp(),
                range.lower(),
                range.upper(),
                validated.metadata());
    }

    record ShipInfo(String shipClass, String sector) {}
}
