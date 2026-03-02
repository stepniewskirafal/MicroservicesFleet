package com.galactic.traderoute.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class RouteRequestTest {

    @Test
    void should_build_with_all_fields() {
        RouteRequest req = RouteRequest.builder()
                .originPortId("SP-ORIGIN")
                .destinationPortId("SP-DEST")
                .shipClass("FREIGHTER")
                .fuelRangeLY(25.0)
                .build();

        assertThat(req.originPortId()).isEqualTo("SP-ORIGIN");
        assertThat(req.destinationPortId()).isEqualTo("SP-DEST");
        assertThat(req.shipClass()).isEqualTo("FREIGHTER");
        assertThat(req.fuelRangeLY()).isEqualTo(25.0);
    }

    @Test
    void should_support_record_equality() {
        RouteRequest a = RouteRequest.builder()
                .originPortId("A")
                .destinationPortId("B")
                .shipClass("SCOUT")
                .fuelRangeLY(10.0)
                .build();
        RouteRequest b = RouteRequest.builder()
                .originPortId("A")
                .destinationPortId("B")
                .shipClass("SCOUT")
                .fuelRangeLY(10.0)
                .build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_differ_when_origin_differs() {
        RouteRequest a = aRequest("SP-A", "SP-B", "SCOUT", 10.0);
        RouteRequest b = aRequest("SP-Z", "SP-B", "SCOUT", 10.0);

        assertThat(a).isNotEqualTo(b);
    }

    private static RouteRequest aRequest(
            String origin, String dest, String shipClass, double fuel) {
        return RouteRequest.builder()
                .originPortId(origin)
                .destinationPortId(dest)
                .shipClass(shipClass)
                .fuelRangeLY(fuel)
                .build();
    }
}
