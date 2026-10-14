package com.galactic.fleet.routeplanning.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class RouteRequestTest {

    @Test
    void should_build_with_all_fields() {
        RouteRequest req = aRequest("SP-ORIGIN", "SP-DEST", "FREIGHTER");

        assertThat(req.originPortId()).isEqualTo("SP-ORIGIN");
        assertThat(req.destinationPortId()).isEqualTo("SP-DEST");
        assertThat(req.shipClass()).isEqualTo("FREIGHTER");
    }

    @Test
    void should_support_record_equality() {
        RouteRequest a = aRequest("A", "B", "SCOUT");
        RouteRequest b = aRequest("A", "B", "SCOUT");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_differ_when_origin_differs() {
        assertThat(aRequest("SP-A", "SP-B", "SCOUT")).isNotEqualTo(aRequest("SP-Z", "SP-B", "SCOUT"));
    }

    private static RouteRequest aRequest(String origin, String dest, String shipClass) {
        return RouteRequest.builder()
                .originPortId(origin)
                .destinationPortId(dest)
                .shipClass(shipClass)
                .build();
    }
}
