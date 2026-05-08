package com.galactic.starport.service.observability;

import com.galactic.starport.repository.DockOccupancyQuery;
import com.galactic.starport.repository.DockOccupancyQuery.DockOccupancySnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MultiGauge.Row;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class DockOccupancyObserver {

    private static final String METRIC_OCCUPANCY_RATIO = "reservations.docks.occupied.ratio";

    private final DockOccupancyQuery query;
    private final MultiGauge occupancyGauge;

    DockOccupancyObserver(DockOccupancyQuery query, MeterRegistry meterRegistry) {
        this.query = query;
        this.occupancyGauge = MultiGauge.builder(METRIC_OCCUPANCY_RATIO)
                .description("Ratio of occupied docking bays per starport (0.0=empty, 1.0=full)."
                        + " Powers the executive dashboard saturation tile.")
                .baseUnit("ratio")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${dock-occupancy.refresh-ms:10000}",
            initialDelayString = "${dock-occupancy.initial-delay-ms:5000}")
    public void refresh() {
        try {
            var snapshots = query.aggregate();
            occupancyGauge.register(
                    snapshots.stream().map(DockOccupancyObserver::toRow).toList(), true);
        } catch (Exception ex) {
            log.warn("Failed to refresh dock occupancy gauge", ex);
        }
    }

    private static Row<?> toRow(DockOccupancySnapshot snapshot) {
        double ratio = snapshot.total() == 0 ? 0.0 : (double) snapshot.occupied() / snapshot.total();
        return Row.of(Tags.of("starport", snapshot.starportCode()), ratio);
    }
}
