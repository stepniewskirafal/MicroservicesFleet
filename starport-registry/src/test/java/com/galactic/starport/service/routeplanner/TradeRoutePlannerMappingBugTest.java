package com.galactic.starport.service.routeplanner;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.galactic.starport.BaseAcceptanceTest;
import com.galactic.starport.service.ReserveBayCommand;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the boundary between "the planner failed" and "we have a bug" on the response side of the
 * wire — the half that {@code ResilienceAspectOrderTest.ourOwnBugNeverReachesTheBreakerAtAll}
 * does not cover (its bug fires before the HTTP call).
 *
 * <p>Two rules, both enforced by {@link TradeRoutePlannerClient}: only a {@code
 * RestClientException} may be relabelled as {@code RoutePlannerUnavailableException}, and the
 * DTO-to-domain mapping happens outside the resilience proxy. Before this, {@code catch
 * (Exception)} wrapped the mapping as well, so a bug in it was retried, recorded by the breaker as
 * a planner failure, counted as {@code infrastructure} and answered with 503 + Retry-After.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "WIREMOCK", mode = ResourceAccessMode.READ_WRITE)
class TradeRoutePlannerMappingBugTest extends BaseAcceptanceTest {

    private static final String PLAN_URL = "/api/v1/routes/plan";
    private static final String ROUTE_JSON =
            """
            {
              "routeId": "ROUTE-MAPPING-TEST",
              "etaHours": 12.5,
              "riskScore": 0.11,
              "correlationId": "mapping-bug-test"
            }
            """;

    @Autowired
    RoutePlanner routePlanner;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    TradeRoutePlannerResponseMapper mapper;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetBreakerAndStubs() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("trade-route-planner");
        circuitBreaker.reset();
        wireMock.resetAll();
    }

    @Test
    void bugInResponseMappingIsNeitherRetriedNorBlamedOnThePlanner() {
        wireMock.stubFor(post(urlEqualTo(PLAN_URL))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ROUTE_JSON)));
        when(mapper.toRoute(any(), any(), any())).thenThrow(new IllegalStateException("mapping bug"));
        double infrastructureBefore = outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE);
        double successBefore = outcome(TradeRoutePlannerClient.OUTCOME_SUCCESS);

        // The bug leaves as what it is — not wrapped into "planner unavailable" (no 503, no
        // Retry-After), and with its own stack trace.
        IllegalStateException bug =
                assertThrows(IllegalStateException.class, () -> routePlanner.calculateRoute(command("MAP-BUG")));
        assertEquals("mapping bug", bug.getMessage());

        // Not retried: a second identical ask cannot fix our code.
        wireMock.verify(1, postRequestedFor(urlEqualTo(PLAN_URL)));
        // Not blamed on the planner: it answered correctly, so the breaker saw one honest success
        // and zero failures — and stayed closed.
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
        // Not counted as infrastructure either: the outcome counter tells the same story.
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE) - infrastructureBefore);
        assertEquals(1.0, outcome(TradeRoutePlannerClient.OUTCOME_SUCCESS) - successBefore);
    }

    // The narrowed catch must still catch what it is there for: an I/O failure on the wire is
    // relabelled, retried (max-attempts 2) and recorded by the breaker — once per attempt.
    @Test
    void transportFailureIsStillRelabelledRetriedAndRecorded() {
        wireMock.stubFor(post(urlEqualTo(PLAN_URL)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        double infrastructureBefore = outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE);

        assertThrows(RoutePlannerUnavailableException.class, () -> routePlanner.calculateRoute(command("RESET")));

        wireMock.verify(2, postRequestedFor(urlEqualTo(PLAN_URL)));
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(2, circuitBreaker.getMetrics().getNumberOfFailedCalls());
        assertEquals(2.0, outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE) - infrastructureBefore);
    }

    private double outcome(String outcome) {
        Counter counter = meterRegistry
                .find(TradeRoutePlannerClient.METRIC_OUTCOME)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
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
