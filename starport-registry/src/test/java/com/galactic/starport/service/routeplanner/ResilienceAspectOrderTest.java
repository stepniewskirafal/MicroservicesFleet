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

/**
 * Locks in the SHIPPED resilience contract — i.e. the resilience4j DEFAULT aspect order,
 * {@code Retry( CircuitBreaker( method ) )}, which is what application.yml runs.
 *
 * <p>What that default means, and why it is worth knowing (issue #2383): the breaker sits inside
 * retry, so it judges every ATTEMPT separately. An operation rescued by retry deposits 1 failure
 * AND 1 success in the sliding window, so the 50% threshold means "50% of attempts", not "50% of
 * operations". The mirror class {@code ResilienceInvertedAspectOrderTest} pins the alternative
 * (profile {@code cb-outer}); the two differ by exactly two integers.
 *
 * <p>The exception-selectivity tests below are order-independent and hold in both worlds: a 4xx
 * domain rejection is neither retried nor recorded, and our own bugs never open the circuit.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "WIREMOCK", mode = ResourceAccessMode.READ_WRITE)
class ResilienceAspectOrderTest extends BaseAcceptanceTest {

    private static final String PLAN_URL = "/api/v1/routes/plan";
    private static final String ROUTE_JSON =
            """
            {
              "routeId": "ROUTE-ORDER-TEST",
              "etaHours": 12.5,
              "riskScore": 0.11,
              "correlationId": "aspect-order-test"
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
        circuitBreaker.reset(); // clears state AND metrics left over from other tests
        wireMock.resetAll();
    }

    /** Every odd request returns 503, every even one succeeds — each operation is rescued by retry. */
    private void stubPlannerFailingEveryOtherRequest(String scenario) {
        wireMock.stubFor(post(urlEqualTo(PLAN_URL))
                .inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("healthy"));
        wireMock.stubFor(post(urlEqualTo(PLAN_URL))
                .inScenario(scenario)
                .whenScenarioStateIs("healthy")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ROUTE_JSON))
                .willSetStateTo(STARTED));
    }

    @Test
    void defaultOrderRecordsEveryAttemptSoOneRescuedOperationDepositsTwoVerdicts() {
        stubPlannerFailingEveryOtherRequest("flaky-planner");

        Route route = routePlanner.calculateRoute(command("DEF-ONE"));

        assertNotNull(route, "retry (outer aspect) should have recovered the operation");
        wireMock.verify(2, postRequestedFor(urlEqualTo(PLAN_URL)));
        // The breaker (inner aspect) judged each attempt separately. Under the cb-outer profile
        // the very same operation is recorded as 1 success / 0 failures.
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    void defaultOrderOpensCircuitMidOperationAndRejectsThatOperationsOwnRetry() {
        stubPlannerFailingEveryOtherRequest("flaky-planner-open");

        // Two operations succeed (each rescued by retry)...
        assertNotNull(routePlanner.calculateRoute(command("DEF-OPEN-1")));
        assertNotNull(routePlanner.calculateRoute(command("DEF-OPEN-2")));

        // ...and the third dies HALFWAY THROUGH: its first attempt is the 5th record in the window
        // (3 failed / 2 successful = 60% >= 50%, minimum-number-of-calls=5), the breaker opens
        // mid-operation and rejects the operation's own rescue-retry. The dependency answered every
        // operation correctly; the caller still gets a failure. This is the cost of attempt-level
        // counting and the reason the cb-outer profile exists.
        assertThrows(RoutePlannerUnavailableException.class, () -> routePlanner.calculateRoute(command("DEF-OPEN-3")));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        // Only 5 requests ever reached the planner — attempt 2 of operation 3 was blocked.
        wireMock.verify(5, postRequestedFor(urlEqualTo(PLAN_URL)));
    }

    // --- order-independent: exception selectivity holds in both worlds ---------------------------

    @Test
    void domainRejectionIsNeitherRetriedNorRecordedByBreaker() {
        wireMock.stubFor(post(urlEqualTo(PLAN_URL)).willReturn(aResponse().withStatus(422)));

        assertThrows(RoutePlanningRejectedException.class, () -> routePlanner.calculateRoute(command("ORDER-422")));

        // Deterministic "no": exactly one ask — a second identical ask cannot change the answer.
        wireMock.verify(1, postRequestedFor(urlEqualTo(PLAN_URL)));
        // Ignored by the breaker: a wave of rejections must not open the circuit
        // against a healthy planner.
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
    }

    // The breaker measures the DEPENDENCY, not us. A bug on our side of the wire (here: a null
    // command hitting Objects.requireNonNull) must not reach the breaker's window AT ALL — not as
    // a failure, and not as a success either. Two changes got us here: record-exceptions stopped
    // our bugs from counting as planner failures, and moving the annotations down to
    // TradeRoutePlannerClient stopped them from reaching the breaker in the first place.
    @Test
    void ourOwnBugNeverReachesTheBreakerAtAll() {
        wireMock.stubFor(post(urlEqualTo(PLAN_URL))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ROUTE_JSON)));

        // More calls than minimum-number-of-calls(5), so the window is definitely evaluated.
        for (int i = 0; i < 6; i++) {
            assertThrows(NullPointerException.class, () -> routePlanner.calculateRoute(null));
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls(), "our bug is not a planner failure");
        // AND not a success either. Before the annotations moved down to TradeRoutePlannerClient
        // these six NPEs landed in resilience4j's third handleThrowable branch and were recorded as
        // six successful calls to a planner that was never asked — phantom successes diluting the
        // failure and slow-call rates.
        assertEquals(
                0,
                circuitBreaker.getMetrics().getNumberOfSuccessfulCalls(),
                "a bug outside the client must not appear in the window at all");
        // The planner was never even asked — the bug fires before the HTTP call.
        wireMock.verify(0, postRequestedFor(urlEqualTo(PLAN_URL)));
        // And a real call still works: the breaker stayed usable throughout.
        assertNotNull(routePlanner.calculateRoute(command("BUG-THEN-OK")));
    }

    // Same principle, the other phantom: a reservation that does not want a route never reaches
    // the planner, so it must not be recorded as a successful call to it.
    @Test
    void noRouteRequestedIsNotRecordedAsASuccessfulPlannerCall() {
        for (int i = 0; i < 6; i++) {
            assertNull(routePlanner.calculateRoute(commandWithoutRoute("NO-ROUTE-" + i)));
        }

        assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
        wireMock.verify(0, postRequestedFor(urlEqualTo(PLAN_URL)));
    }

    private ReserveBayCommand commandWithoutRoute(String suffix) {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEST-" + suffix)
                .startStarportCode("ORIGIN-" + suffix)
                .customerCode("CUST-" + suffix)
                .shipCode("SS-" + suffix)
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2009-01-01T00:00:00Z"))
                .endAt(Instant.parse("2009-01-01T01:00:00Z"))
                .requestRoute(false)
                .build();
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
