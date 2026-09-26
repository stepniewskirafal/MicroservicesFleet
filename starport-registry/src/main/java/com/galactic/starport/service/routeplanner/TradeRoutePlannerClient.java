package com.galactic.starport.service.routeplanner;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transport + resilience for the trade-route-planner call — and nothing else.
 *
 * <p>This class exists so that the scope of {@code @CircuitBreaker} is EXACTLY the remote call.
 * The annotations used to sit on {@code TradeRoutePlannerHttpAdapter.calculateRoute}, which also
 * validated arguments, short-circuited on {@code requestRoute=false} and mapped the domain object
 * to a DTO. Everything that threw in those steps entered the breaker's sliding window even though
 * it never touched the network, and the {@code requestRoute=false} early return was recorded as a
 * successful call to a planner that was never asked — phantom successes diluting the failure and
 * slow-call rates. The breaker answers one question, "is trade-route-planner healthy", so it may
 * only ever observe the wire.
 *
 * <p>For the same reason this class returns the planner's DTO, not a domain {@code Route}: the
 * DTO-to-domain mapping happens in the adapter, OUTSIDE the resilience proxy. And the only thing
 * this class ever relabels as "planner unavailable" is a {@link RestClientException} — connect,
 * read-timeout, body extraction. A catch-all here would turn our own bug into a planner failure:
 * retried, recorded by the breaker, reported to the client as 503 + Retry-After, and logged
 * without a stack trace. Five such bugs would open the circuit against a perfectly healthy
 * planner.
 *
 * <p>The class is package-private (not API) but the method is public on purpose: Spring's CGLIB
 * proxy reliably advises public methods only.
 */
@Component
@Slf4j
class TradeRoutePlannerClient {

    private static final String ROUTE_PLAN_URI = "/api/v1/routes/plan";

    /**
     * One meter, one tag, every outcome — so a success ratio is a single PromQL expression instead
     * of a join across two metric names. Deliberately NOT called "reservations.route.plan": that
     * name already belongs to the Observation's Timer, and Micrometer rejects two meter types
     * sharing a name.
     */
    static final String METRIC_OUTCOME = "reservations.route.plan.outcome";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_DOMAIN = "domain";
    static final String OUTCOME_INFRASTRUCTURE = "infrastructure";
    static final String OUTCOME_EMPTY_RESPONSE = "empty_response";
    static final String OUTCOME_CIRCUIT_OPEN = "circuit_open";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> outcomeCounters = new ConcurrentHashMap<>();

    TradeRoutePlannerClient(RestClient tradeRoutePlannerRestClient, MeterRegistry meterRegistry) {
        this.restClient = tradeRoutePlannerRestClient;
        this.meterRegistry = meterRegistry;
    }

    @Retry(name = "trade-route-planner")
    @CircuitBreaker(name = "trade-route-planner", fallbackMethod = "circuitOpenFallback")
    public TradeRoutePlannerResponse plan(TradeRoutePlannerRequest request, String origin, String destination) {
        final TradeRoutePlannerResponse response;
        try {
            response = restClient
                    .post()
                    .uri(ROUTE_PLAN_URI)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        // 4xx = domain rejection ("this route cannot be planned") — deterministic,
                        // so it must be neither retried nor recorded by the circuit breaker.
                        count(OUTCOME_DOMAIN);
                        throw new RoutePlanningRejectedException(
                                origin, destination, res.getStatusCode().value());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        // 5xx = infrastructure failure (planner is down) — transient, retryable.
                        count(OUTCOME_INFRASTRUCTURE);
                        throw new RoutePlannerUnavailableException(
                                origin, destination, res.getStatusCode().value());
                    })
                    .body(TradeRoutePlannerResponse.class);
        } catch (RouteUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            // Timeout / connection failure / unreadable body — an EXPECTED dependency failure, not
            // a bug in this service: one WARN line, no stack trace. The cause still travels up
            // inside the exception for anyone who needs it. Deliberately NOT catch (Exception):
            // anything that is not a RestClientException did not come from the wire and must leave
            // this method as what it is — our own bug, with its own type and its own stack trace.
            count(OUTCOME_INFRASTRUCTURE);
            log.warn("Failed to plan route from {} to {}: {}", origin, destination, e.toString());
            throw new RoutePlannerUnavailableException(origin, destination, e);
        }

        if (response == null) {
            count(OUTCOME_EMPTY_RESPONSE);
            throw new RoutePlannerUnavailableException(
                    origin, destination, RoutePlannerUnavailableException.NO_RESPONSE);
        }

        count(OUTCOME_SUCCESS);
        return response;
    }

    // Fallback matches by exception parameter type, so this fires ONLY when the circuit is open.
    // Every other failure propagates untouched — a Throwable-typed fallback would swallow our own
    // bugs and mislabel them as circuit_open in metrics.
    private TradeRoutePlannerResponse circuitOpenFallback(
            TradeRoutePlannerRequest request, String origin, String destination, CallNotPermittedException e) {
        count(OUTCOME_CIRCUIT_OPEN);
        log.warn("Circuit open for trade-route-planner, failing fast: {} -> {}", origin, destination);
        throw new RoutePlannerUnavailableException(origin, destination, e);
    }

    private void count(String outcome) {
        outcomeCounters
                .computeIfAbsent(outcome, value -> Counter.builder(METRIC_OUTCOME)
                        .description("Route planning attempts by outcome")
                        .tag("outcome", value)
                        .register(meterRegistry))
                .increment();
    }
}
