package com.galactic.starport.service.routeplanner;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.*;

import com.galactic.starport.BaseAcceptanceTest;
import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/**
 * The A/B counterpart of {@link ResilienceAspectOrderTest}: identical dependency, identical
 * traffic, but with the {@code cb-outer} profile active — CircuitBreaker OUTSIDE Retry. The only
 * difference between the two worlds is two integers in application-cb-outer.yml; that is the whole
 * point, because the swap is trivial while the consequences are not.
 *
 * <p>Operation-level counting (here) vs attempt-level counting (default):
 *
 * <ul>
 *   <li>a retry-rescued operation is recorded as 1 success / 0 failures, not 1+1;
 *   <li>the 50% threshold means "50% of operations" and does not drift with max-attempts;
 *   <li>three rescued operations in a row leave the circuit CLOSED — under the default the third
 *       one opens it mid-flight and gets its own rescue-retry rejected.
 * </ul>
 *
 * <p>Neither order is "wrong": attempt-level counting trades threshold predictability for faster
 * reaction to a hard outage. What is indefensible is not knowing which one you run.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "WIREMOCK", mode = ResourceAccessMode.READ_WRITE)
@ActiveProfiles({"test", "cb-outer"})
class ResilienceInvertedAspectOrderTest extends BaseAcceptanceTest {

    private static final String PLAN_URL = "/api/v1/routes/plan";
    private static final String ROUTE_JSON =
            """
            {
              "routeId": "ROUTE-INVERTED-TEST",
              "etaHours": 12.5,
              "riskScore": 0.11,
              "correlationId": "inverted-order-test"
            }
            """;

    @Autowired
    RoutePlanner routePlanner;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetBreakerAndStubs() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("trade-route-planner");
        circuitBreaker.reset();
        wireMock.resetAll();
        stubPlannerFailingEveryOtherRequest();
    }

    private void stubPlannerFailingEveryOtherRequest() {
        wireMock.stubFor(post(urlEqualTo(PLAN_URL))
                .inScenario("flaky-planner-inverted")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("healthy"));
        wireMock.stubFor(post(urlEqualTo(PLAN_URL))
                .inScenario("flaky-planner-inverted")
                .whenScenarioStateIs("healthy")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ROUTE_JSON))
                .willSetStateTo(STARTED));
    }

    @Test
    void breakerRecordsOneVerdictPerOperationNotPerRetryAttempt() {
        Route route = routePlanner.calculateRoute(command("INV-ONE"));

        assertNotNull(route, "retry (now the inner aspect) should have recovered the operation");
        wireMock.verify(2, postRequestedFor(urlEqualTo(PLAN_URL)));
        // The breaker (outer aspect) saw ONE call carrying the FINAL outcome. The default order
        // records 1 failure + 1 success for this very same operation.
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    void threeRescuedOperationsLeaveTheCircuitClosed() {
        for (int i = 1; i <= 3; i++) {
            assertNotNull(routePlanner.calculateRoute(command("INV-OPEN-" + i)), "operation " + i + " should succeed");
        }

        // All 6 attempts reached the planner (the default order stops at 5 — see the mirror test)
        // and the breaker judged OPERATIONS: 3 successes, 0 failures, circuit CLOSED.
        wireMock.verify(6, postRequestedFor(urlEqualTo(PLAN_URL)));
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(3, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
    }

    private ReserveBayCommand command(String suffix) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEST-" + suffix)
                .startStarportCode("ORIGIN-" + suffix)
                .customerCode("CUST-" + suffix)
                .shipCode("SS-" + suffix)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2009-01-01T00:00:00Z"))
                .endAt(Instant.parse("2009-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();
    }
}
