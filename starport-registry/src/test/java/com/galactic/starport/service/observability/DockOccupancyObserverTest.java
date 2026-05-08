package com.galactic.starport.service.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.galactic.starport.repository.DockOccupancyQuery;
import com.galactic.starport.repository.DockOccupancyQuery.DockOccupancySnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class DockOccupancyObserverTest {

    private static final String METRIC = "reservations.docks.occupied.ratio";

    private SimpleMeterRegistry registry;
    private DockOccupancyQuery query;
    private DockOccupancyObserver observer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        query = mock(DockOccupancyQuery.class);
        observer = new DockOccupancyObserver(query, registry);
    }

    @Test
    void should_publish_occupancy_ratio_per_starport_when_snapshots_returned() {
        when(query.aggregate())
                .thenReturn(List.of(
                        new DockOccupancySnapshot("TYRA", 6, 10),
                        new DockOccupancySnapshot("VEGA", 3, 10),
                        new DockOccupancySnapshot("ORION", 5, 5)));

        observer.refresh();

        assertThat(gaugeValue("TYRA")).isEqualTo(0.6);
        assertThat(gaugeValue("VEGA")).isEqualTo(0.3);
        assertThat(gaugeValue("ORION")).isEqualTo(1.0);
    }

    @Test
    void should_emit_zero_ratio_when_starport_has_no_bays() {
        when(query.aggregate()).thenReturn(List.of(new DockOccupancySnapshot("EMPTY", 0, 0)));

        observer.refresh();

        assertThat(gaugeValue("EMPTY")).isEqualTo(0.0);
    }

    @Test
    void should_overwrite_previous_snapshot_when_refresh_runs_again() {
        when(query.aggregate())
                .thenReturn(List.of(new DockOccupancySnapshot("TYRA", 8, 10)))
                .thenReturn(List.of(new DockOccupancySnapshot("TYRA", 4, 10)));

        observer.refresh();
        observer.refresh();

        assertThat(gaugeValue("TYRA")).isEqualTo(0.4);
    }

    @Test
    void should_swallow_repository_exception_to_protect_scheduler() {
        when(query.aggregate()).thenThrow(new RuntimeException("DB down"));

        assertThatCode(observer::refresh).doesNotThrowAnyException();
    }

    @Test
    void should_register_gauge_with_base_unit_ratio() {
        when(query.aggregate()).thenReturn(List.of(new DockOccupancySnapshot("TYRA", 1, 1)));

        observer.refresh();

        var meter = registry.get(METRIC).tag("starport", "TYRA").gauge();
        assertThat(meter.getId().getBaseUnit()).isEqualTo("ratio");
    }

    private double gaugeValue(String starport) {
        return registry.get(METRIC).tag("starport", starport).gauge().value();
    }
}
