package com.galactic.starport.service.routeplanner;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.galactic.starport.BaseAcceptanceTest;
import com.galactic.starport.service.ReserveBayCommand;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The real {@code @LoadBalanced} client against a registry that knows no planner (ADR-0042).
 *
 * <p>Discovery is switched on so {@code RestClientConfig} builds the load-balanced client, Eureka
 * stays off, and the fallback {@code SimpleDiscoveryClient} knows nothing about {@code
 * trade-route-planner} — the same situation as a typo in {@code base-url} or every replica evicted.
 * Nothing is stubbed: the point is that no request can reach WireMock at all.
 *
 * <p>Pins three things: the library's exact message (the only handle {@link TradeRoutePlannerClient}
 * has), the full aspect stack's behaviour (retried once, two breaker failures, outcome {@code
 * no_instances} per attempt) and the HTTP contract (503 + Retry-After, not 500).
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "WIREMOCK", mode = ResourceAccessMode.READ_WRITE)
@TestPropertySource(
        properties = {
            "spring.cloud.discovery.enabled=true",
            "eureka.client.enabled=false",
            "app.trade-route-planner.base-url=http://trade-route-planner"
        })
class NoPlannerInstanceInRegistryTest extends BaseAcceptanceTest {

    private static final String PLAN_URL = "/api/v1/routes/plan";

    @Autowired
    RoutePlanner routePlanner;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    MeterRegistry meterRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetBreaker() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("trade-route-planner");
        circuitBreaker.reset();
        wireMock.resetAll();
    }

    @Test
    void registryWithoutThePlannerIsUnavailableNotOurBug() {
        double noInstancesBefore = outcome(TradeRoutePlannerClient.OUTCOME_NO_INSTANCES);
        double infrastructureBefore = outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE);
        double misroutedBefore = outcome(TradeRoutePlannerClient.OUTCOME_MISROUTED);

        RoutePlannerUnavailableException ex = assertThrows(
                RoutePlannerUnavailableException.class, () -> routePlanner.calculateRoute(command("NO-INSTANCE")));

        // The library's verdict, verbatim — a Spring Cloud bump that rewords it must fail here,
        // not silently reopen the gap in production. With spring-retry on the classpath the chain
        // is DeferringLoadBalancerInterceptor -> RetryLoadBalancerInterceptor -> the 3-arg
        // BlockingLoadBalancerClient#execute, whose null-instance guard is an IllegalArgumentException.
        Throwable cause = ex.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertEquals(
                TradeRoutePlannerClient.NO_INSTANCE_CHOSEN_MESSAGE_PREFIX + ", serviceId: trade-route-planner",
                cause.getMessage());

        // Not a single request left the JVM.
        wireMock.verify(0, postRequestedFor(urlEqualTo(PLAN_URL)));
        // Unavailable = retried once (max-attempts 2) and every attempt is a breaker failure:
        // the planner really is unreachable, and a half-open probe must NOT close the circuit.
        assertEquals(2.0, outcome(TradeRoutePlannerClient.OUTCOME_NO_INSTANCES) - noInstancesBefore);
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(2, circuitBreaker.getMetrics().getNumberOfFailedCalls());
        // Its own label — neither diluted into "infrastructure" nor mistaken for a 4xx.
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_INFRASTRUCTURE) - infrastructureBefore);
        assertEquals(0.0, outcome(TradeRoutePlannerClient.OUTCOME_MISROUTED) - misroutedBefore);
    }

    @Test
    void customerGets503WithRetryAfterAndTheHoldIsReleased() throws Exception {
        purgeAndReset();
        String starport = "DEST-NO-INST";
        seedDefaultReservationFixture(starport, Map.of("originCode", "ALPHA-BASE-NI"));
        Map<String, Object> withRoute = makePayload(Map.of("requestRoute", true, "originPortId", "ALPHA-BASE-NI"));

        ResponseEntity<String> resp = postReservation(starport, withRoute);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("10", resp.getHeaders().getFirst("Retry-After"));
        assertEquals("ROUTE_PLANNER_UNAVAILABLE", parseJson(resp).get("error"));
        // TX1's HOLD was compensated on the way out — no bay stays blocked behind a 503.
        assertEquals(0, jdbc.queryForObject("select count(*) from reservation where status = 'HOLD'", Integer.class));
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
