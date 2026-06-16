package com.galactic.traderoute.application;

import com.galactic.traderoute.adapter.out.metrics.MicrometerRouteMetricsAdapter;
import com.galactic.traderoute.domain.model.RouteRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Verifies that a single plan is broken down into named child spans so that in Tempo the latency of a
 * slow plan is attributable to a phase — the CPU-bound routing math ({@code routes.plan.compute}) vs.
 * the Kafka publish hop ({@code routes.plan.publish}) — nested under the parent {@code routes.plan}.
 */
@Execution(ExecutionMode.CONCURRENT)
class PlanRouteServiceObservabilityTest {

    private TestObservationRegistry observationRegistry;
    private PlanRouteService service;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        service = new PlanRouteService(
                new MicrometerRouteMetricsAdapter(new SimpleMeterRegistry(), observationRegistry), event -> {});
    }

    @Test
    void planEmitsParentAndPerPhaseChildSpans() {
        service.planRoute(RouteRequest.builder()
                .originPortId("SP-A")
                .destinationPortId("SP-B")
                .shipClass("FREIGHTER")
                .fuelRangeLY(25.0)
                .build());

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("routes.plan")
                .that()
                .hasBeenStarted()
                .hasBeenStopped();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("routes.plan.compute")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasParentObservationContextMatching(ctx -> "routes.plan".equals(ctx.getName()));

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("routes.plan.publish")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasParentObservationContextMatching(ctx -> "routes.plan".equals(ctx.getName()));
    }
}
