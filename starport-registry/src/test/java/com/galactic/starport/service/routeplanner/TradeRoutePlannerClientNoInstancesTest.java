package com.galactic.starport.service.routeplanner;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The relabelling rule for the one failure that happens before any byte leaves the JVM (ADR-0042),
 * tested WITHOUT Spring, aspects or a registry: a {@link RestClient} whose interceptor throws
 * stands in for Spring Cloud LoadBalancer's interceptor, which is exactly where the real exception
 * comes from. The real load balancer, with its real message, is pinned by {@link
 * NoPlannerInstanceInRegistryTest}.
 *
 * <p>Two rules: the registry's verdict (either of the library's two bare JDK exceptions, matched
 * by type AND message prefix) becomes {@link RoutePlannerUnavailableException} under outcome
 * {@code no_instances}; every OTHER {@code IllegalArgumentException} / {@code
 * IllegalStateException} is ours and leaves untouched — same instance, no outcome, no relabel.
 */
class TradeRoutePlannerClientNoInstancesTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    // --- the registry's verdict, both shapes the library can produce ---------------------------

    @Test
    void noInstanceChosenByTheRetryingInterceptorIsRelabelledUnavailableAndCountedAsNoInstances() {
        // What ships: spring-retry on the classpath -> RetryLoadBalancerInterceptor -> 3-arg execute.
        TradeRoutePlannerClient client = clientWhoseTransportThrows(new IllegalArgumentException(
                TradeRoutePlannerClient.NO_INSTANCE_CHOSEN_MESSAGE_PREFIX + ", serviceId: trade-route-planner"));

        RoutePlannerUnavailableException ex =
                assertThrows(RoutePlannerUnavailableException.class, () -> client.plan(request(), "SP-A", "SP-B"));

        assertEquals(RoutePlannerUnavailableException.NO_RESPONSE, ex.getStatusCode());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        // The 503 body carries a reason a caller can act on, not the cause's class name.
        assertTrue(String.valueOf(ex.getMessage()).contains("no planner instance registered"), ex.getMessage());
        assertEquals(1.0, outcome(TradeRoutePlannerClient.OUTCOME_NO_INSTANCES));
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE));
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_MISROUTED));
    }

    @Test
    void noInstancesAvailableFromThePlainInterceptorIsRelabelledTheSameWay() {
        // Without spring-retry: LoadBalancerInterceptor -> 2-arg execute -> IllegalStateException.
        TradeRoutePlannerClient client = clientWhoseTransportThrows(new IllegalStateException(
                TradeRoutePlannerClient.NO_INSTANCES_AVAILABLE_MESSAGE_PREFIX + "trade-route-planner"));

        RoutePlannerUnavailableException ex =
                assertThrows(RoutePlannerUnavailableException.class, () -> client.plan(request(), "SP-A", "SP-B"));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(1.0, outcome(TradeRoutePlannerClient.OUTCOME_NO_INSTANCES));
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE));
    }

    // --- everything else is OUR bug and must leave untouched -----------------------------------

    @Test
    void anyOtherIllegalArgumentExceptionIsOurBugAndLeavesUntouched() {
        IllegalArgumentException bug = new IllegalArgumentException("origin must not be blank");
        assertLeavesUntouched(bug);
    }

    @Test
    void anyOtherIllegalStateExceptionIsOurBugAndLeavesUntouched() {
        IllegalStateException bug = new IllegalStateException("some invariant of ours broke");
        assertLeavesUntouched(bug);
    }

    @Test
    void exceptionWithoutMessageLeavesUntouched() {
        assertLeavesUntouched(new IllegalArgumentException((String) null));
        assertLeavesUntouched(new IllegalStateException((String) null));
    }

    @Test
    void messageMerelyMentioningThePhraseIsNotTheRegistrysVerdict() {
        // Prefix match, not substring: a wrapped or rephrased message is not the load balancer's.
        assertLeavesUntouched(new IllegalArgumentException(
                "wrapped: " + TradeRoutePlannerClient.NO_INSTANCE_CHOSEN_MESSAGE_PREFIX + ", serviceId: x"));
        assertLeavesUntouched(new IllegalStateException(
                "wrapped: " + TradeRoutePlannerClient.NO_INSTANCES_AVAILABLE_MESSAGE_PREFIX + "x"));
    }

    @Test
    void rightMessageOnTheWrongTypeIsNotTheRegistrysVerdict() {
        // Type AND prefix: the library never crosses them, so a crossed pair is somebody else's.
        assertLeavesUntouched(new IllegalStateException(
                TradeRoutePlannerClient.NO_INSTANCE_CHOSEN_MESSAGE_PREFIX + ", serviceId: trade-route-planner"));
        assertLeavesUntouched(new IllegalArgumentException(
                TradeRoutePlannerClient.NO_INSTANCES_AVAILABLE_MESSAGE_PREFIX + "trade-route-planner"));
    }

    private void assertLeavesUntouched(RuntimeException bug) {
        TradeRoutePlannerClient client = clientWhoseTransportThrows(bug);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.plan(request(), "SP-A", "SP-B"));

        assertSame(bug, thrown, "our own bug must keep its type, its message and its stack trace");
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_NO_INSTANCES));
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE));
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_MISROUTED));
    }

    private TradeRoutePlannerClient clientWhoseTransportThrows(RuntimeException failure) {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://trade-route-planner")
                .requestInterceptor((request, body, execution) -> {
                    throw failure;
                })
                .build();
        return new TradeRoutePlannerClient(restClient, meterRegistry, new ObjectMapper());
    }

    private static TradeRoutePlannerRequest request() {
        return new TradeRoutePlannerRequest("SP-A", "SP-B", new TradeRoutePlannerRequest.ShipProfileDto("SCOUT", 50.0));
    }

    private double outcome(String outcome) {
        Counter counter = meterRegistry
                .find(TradeRoutePlannerClient.METRIC_OUTCOME)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
