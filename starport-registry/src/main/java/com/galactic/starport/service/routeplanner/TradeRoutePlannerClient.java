package com.galactic.starport.service.routeplanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
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
 * <p>A 4xx is a domain rejection ONLY when it is the planner's deliberate one: 422 with error code
 * {@code ROUTE_REJECTED} in the body. Any other 4xx (400 from our broken serializer, 404 from a
 * wrong URL, 422 rejecting our DTO's shape) is OUR contract bug, not the planner saying "no" —
 * it leaves as {@link PlannerContractException}: on no resilience4j list, counted as
 * {@code misrouted}, answered with 502. Matching by status alone is not enough: the planner's
 * bean validation answers 422 too (ADR-0040).
 *
 * <p>One failure happens BEFORE a single byte leaves the JVM: the {@code @LoadBalanced} client
 * resolves the host of {@code base-url} as a service id in the registry, and when the registry
 * knows no instance (a typo in {@code base-url}, or every replica evicted after a long outage)
 * Spring Cloud LoadBalancer throws a bare JDK exception ({@code IllegalArgumentException} or
 * {@code IllegalStateException}, see the prefix constants) — not a {@link RestClientException},
 * so until ADR-0042 it escaped this class uncounted and reached the client as 500. It is now relabelled {@link RoutePlannerUnavailableException} (503 + Retry-After,
 * retried, recorded by the breaker — the planner really is unreachable) under its own outcome
 * {@code no_instances}, which a healthy system keeps at zero (ADR-0042).
 *
 * <p>The class is package-private (not API) but the method is public on purpose: Spring's CGLIB
 * proxy reliably advises public methods only.
 */
@Component
@Slf4j
class TradeRoutePlannerClient {

    private static final String ROUTE_PLAN_URI = "/api/v1/routes/plan";

    /** The planner's error code for "this route cannot be planned" (RouteRejectedResponse.error). */
    static final String ROUTE_REJECTED_ERROR_CODE = "ROUTE_REJECTED";

    private static final int BODY_SNIPPET_MAX_CHARS = 200;

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
    /** A 4xx outside the planner's contract — ours, not theirs. A healthy system keeps this at zero. */
    static final String OUTCOME_MISROUTED = "misrouted";
    /**
     * The service registry knows no planner instance, so no request was sent: a typo in {@code
     * base-url} or every replica evicted. A healthy system keeps this at zero (ADR-0042).
     */
    static final String OUTCOME_NO_INSTANCES = "no_instances";

    /** Every value the {@code outcome} tag can take — all registered at zero in the constructor. */
    static final List<String> ALL_OUTCOMES = List.of(
            OUTCOME_SUCCESS,
            OUTCOME_DOMAIN,
            OUTCOME_INFRASTRUCTURE,
            OUTCOME_EMPTY_RESPONSE,
            OUTCOME_CIRCUIT_OPEN,
            OUTCOME_MISROUTED,
            OUTCOME_NO_INSTANCES);

    /**
     * Spring Cloud LoadBalancer has no dedicated exception type for "no instance to pick", only
     * two bare JDK exceptions from {@code BlockingLoadBalancerClient}, and WHICH one depends on the
     * classpath: with spring-retry present (our case — spring-kafka pulls it in) the
     * {@code RetryLoadBalancerInterceptor} chooses the instance itself and calls the
     * three-argument {@code execute}, which throws {@code IllegalArgumentException("Service
     * Instance cannot be null, serviceId: …")}; without it the plain {@code LoadBalancerInterceptor}
     * calls the two-argument {@code execute}, which throws {@code IllegalStateException("No
     * instances available for …")}. Both are matched by type + message prefix — the only handle we
     * have. {@code NoPlannerInstanceInRegistryTest} runs the real load balancer so a library bump
     * that rewords either message fails a test instead of silently reopening the gap.
     */
    static final String NO_INSTANCE_CHOSEN_MESSAGE_PREFIX = "Service Instance cannot be null";

    static final String NO_INSTANCES_AVAILABLE_MESSAGE_PREFIX = "No instances available for ";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Map<String, Counter> outcomeCounters = new ConcurrentHashMap<>();

    TradeRoutePlannerClient(
            RestClient tradeRoutePlannerRestClient, MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.restClient = tradeRoutePlannerRestClient;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        // Every outcome series is registered HERE, at zero, before the first call — not lazily on
        // its first occurrence. The reason is PromQL's increase(): it measures the difference between
        // samples inside its window and does not assume that a series started at zero. A counter
        // created lazily is first scraped already at 1, with no earlier sample to subtract, so
        // increase() yields 0 and the TradeRoutePlannerUnexpectedOutcome alert ("must stay at zero":
        // misrouted / empty_response / no_instances) stays silent for the FIRST event after every
        // restart — i.e. right after a deploy, exactly when a contract bug is most likely. Scraped
        // once at 0, the very first event is a visible 0 -> 1 step.
        //
        // What this does NOT fix: we are still blind until Prometheus has scraped that zero once,
        // i.e. for up to one scrape interval after startup (scrape_interval in prometheus.yml —
        // 3 s in this stack, 15 s in a default Prometheus). An event inside that window is again
        // born at 1.
        ALL_OUTCOMES.forEach(outcome -> outcomeCounters.put(outcome, registerOutcomeCounter(outcome)));
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
                        int status = res.getStatusCode().value();
                        String body = readBody(res);
                        if (isDeliberateRejection(status, body)) {
                            // 422 ROUTE_REJECTED = domain rejection ("this route cannot be planned")
                            // — deterministic, so neither retried nor recorded by the breaker.
                            count(OUTCOME_DOMAIN);
                            throw new RoutePlanningRejectedException(origin, destination, status);
                        }
                        // Any other 4xx is OUR bug or misconfiguration wearing the planner's
                        // uniform. Not retried, not a planner failure, not a customer conflict.
                        count(OUTCOME_MISROUTED);
                        log.error(
                                "Planner answered HTTP {} outside its contract for {} -> {}: {}",
                                status,
                                origin,
                                destination,
                                body);
                        throw new PlannerContractException(origin, destination, status, body);
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
            // The single exception to that rule is the load balancer's own verdict, below.
            count(OUTCOME_INFRASTRUCTURE);
            log.warn("Failed to plan route from {} to {}: {}", origin, destination, e.toString());
            throw new RoutePlannerUnavailableException(origin, destination, e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (!isNoInstanceInRegistry(e)) {
                // Not the load balancer's verdict — some other JDK exception, i.e. our own bug.
                // It leaves exactly as it is: own type, own stack trace, no outcome, no relabel.
                throw e;
            }
            // The registry knows no instance of the planner, so nothing was sent. Either base-url
            // names a service the registry has never heard of (our config, deterministic) or every
            // replica was evicted after a long outage (transient). The adapter cannot tell them
            // apart, so it chooses the mapping that never hides an outage: unavailable (503 +
            // Retry-After, retried, breaker failure) — the typo is caught by the outcome's own
            // "must be zero" alert instead. ADR-0042.
            count(OUTCOME_NO_INSTANCES);
            log.warn("No planner instance in the registry for {} -> {}: {}", origin, destination, e.getMessage());
            throw new RoutePlannerUnavailableException(
                    origin, destination, "no planner instance registered in the service registry", e);
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

    /** The planner's deliberate "no": 422 whose body carries {@code error: ROUTE_REJECTED}. */
    private boolean isDeliberateRejection(int status, String body) {
        if (status != HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return false;
        }
        try {
            return ROUTE_REJECTED_ERROR_CODE.equals(
                    objectMapper.readTree(body).path("error").asText());
        } catch (IOException | RuntimeException e) {
            // Not JSON, or not the shape we agreed on — that is a contract problem, not a rejection.
            return false;
        }
    }

    /**
     * True only for Spring Cloud LoadBalancer's own "no instance" verdicts (see the prefix
     * constants). Type AND prefix must match: any other {@link IllegalArgumentException} or {@link
     * IllegalStateException} is ours and must not be relabelled.
     */
    private static boolean isNoInstanceInRegistry(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        if (e instanceof IllegalArgumentException) {
            return message.startsWith(NO_INSTANCE_CHOSEN_MESSAGE_PREFIX);
        }
        return e instanceof IllegalStateException && message.startsWith(NO_INSTANCES_AVAILABLE_MESSAGE_PREFIX);
    }

    private static String readBody(ClientHttpResponse res) {
        try {
            String body = StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8);
            return body.length() > BODY_SNIPPET_MAX_CHARS ? body.substring(0, BODY_SNIPPET_MAX_CHARS) + "…" : body;
        } catch (IOException e) {
            return "<unreadable body: " + e.getClass().getSimpleName() + ">";
        }
    }

    private void count(String outcome) {
        // Every known outcome is already in the map (see the constructor); computeIfAbsent only
        // keeps an outcome added without being listed in ALL_OUTCOMES from failing the call.
        outcomeCounters.computeIfAbsent(outcome, this::registerOutcomeCounter).increment();
    }

    private Counter registerOutcomeCounter(String outcome) {
        return Counter.builder(METRIC_OUTCOME)
                .description("Route planning attempts by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
