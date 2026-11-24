package com.galactic.fleet.routeplanning.internal;

import com.galactic.fleet.routeplanning.api.RouteRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Verifies the spans of a single plan: the parent {@code routes.plan} and the routing math as its
 * named child ({@code routes.plan.compute}). There is no publish span any more — planning performs
 * no I/O (ADR-0043).
 */
@Execution(ExecutionMode.CONCURRENT)
class PlanRouteServiceObservabilityTest {

    private TestObservationRegistry observationRegistry;
    private PlanRouteService service;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        service = new PlanRouteService(
                new MicrometerRouteMetrics(new SimpleMeterRegistry(), observationRegistry), new ShipFuelRanges());
    }

    @Test
    void planEmitsParentAndComputeChildSpan() {
        service.planRoute(RouteRequest.builder()
                .originPortId("SP-A")
                .destinationPortId("SP-B")
                .shipClass("FREIGHTER")
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
    }

    @Test
    void planningDoesNotPublishAnything() {
        service.planRoute(RouteRequest.builder()
                .originPortId("SP-A")
                .destinationPortId("SP-B")
                .shipClass("FREIGHTER")
                .build());

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasNumberOfObservationsEqualTo(2)
                .hasNumberOfObservationsWithNameEqualTo("routes.plan.publish", 0);
    }
}
