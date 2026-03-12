package com.galactic.traderoute.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class PlannedRouteTest {

    @Test
    void should_build_with_all_fields() {
        PlannedRoute route = PlannedRoute.builder()
                .routeId("ROUTE-ABCD1234")
                .etaHours(18.5)
                .riskScore(0.42)
                .build();

        assertThat(route.routeId()).isEqualTo("ROUTE-ABCD1234");
        assertThat(route.etaHours()).isEqualTo(18.5);
        assertThat(route.riskScore()).isEqualTo(0.42);
    }

    @Test
    void should_support_record_equality() {
        PlannedRoute a = PlannedRoute.builder()
                .routeId("ROUTE-X")
                .etaHours(10.0)
                .riskScore(0.5)
                .build();
        PlannedRoute b = PlannedRoute.builder()
                .routeId("ROUTE-X")
                .etaHours(10.0)
                .riskScore(0.5)
                .build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_differ_when_any_field_differs() {
        PlannedRoute base = PlannedRoute.builder()
                .routeId("ROUTE-A")
                .etaHours(10.0)
                .riskScore(0.5)
                .build();
        PlannedRoute diffId = PlannedRoute.builder()
                .routeId("ROUTE-B")
                .etaHours(10.0)
                .riskScore(0.5)
                .build();
        PlannedRoute diffEta = PlannedRoute.builder()
                .routeId("ROUTE-A")
                .etaHours(99.0)
                .riskScore(0.5)
                .build();

        assertThat(base).isNotEqualTo(diffId);
        assertThat(base).isNotEqualTo(diffEta);
    }
}
