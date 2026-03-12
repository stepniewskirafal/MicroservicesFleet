package com.galactic.starport.service.routeplanner;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Primary
@Service
@Slf4j
class TradeRoutePlannerHttpAdapter implements RoutePlanner {

    private static final String ROUTE_PLAN_URI = "/routes/plan";
    private static final String OBSERVATION_NAME = "reservations.route.plan";
    private static final String METRIC_ROUTE_PLAN_SUCCESS = "reservations.route.plan.success";
    private static final String METRIC_ROUTE_PLAN_ERROR = "reservations.route.plan.errors";
    private static final double FUEL_RANGE_SCOUT = 15.0;
    private static final double FUEL_RANGE_FREIGHTER = 25.0;
    private static final double FUEL_RANGE_CRUISER = 40.0;
    private static final double FUEL_RANGE_UNKNOWN = 5.0;

    private final RestClient restClient;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Counter routePlanSuccessCounter;

    TradeRoutePlannerHttpAdapter(
            RestClient tradeRoutePlannerRestClient,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry) {
        this.restClient = tradeRoutePlannerRestClient;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.routePlanSuccessCounter = Counter.builder(METRIC_ROUTE_PLAN_SUCCESS)
                .description("Number of successfully planned routes")
                .register(meterRegistry);
    }

    @Override
    @CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailableFallback")
    public Route calculateRoute(ReserveBayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (!command.requestRoute()) {
            return null;
        }
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("startStarport", Objects.toString(command.startStarportCode(), "unknown"))
                .lowCardinalityKeyValue("destinationStarport", command.destinationStarportCode())
                .observe(() -> callTradeRoutePlanner(command));
    }

    private Route routeUnavailableFallback(ReserveBayCommand command, Throwable t) {
        incrementErrorCounter("circuit_open");
        log.warn(
                "Circuit breaker open for trade-route-planner, route unavailable: {} -> {}",
                command.startStarportCode(),
                command.destinationStarportCode());
        throw new RouteUnavailableException(command.startStarportCode(), command.destinationStarportCode(), t);
    }

    private Route callTradeRoutePlanner(ReserveBayCommand command) {
        TradeRoutePlannerRequest request = buildRequest(command);
        try {
            TradeRoutePlannerResponse response = restClient
                    .post()
                    .uri(ROUTE_PLAN_URI)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        // 4xx = domain rejection (route cannot be planned for this origin/destination)
                        incrementErrorCounter("domain");
                        throw new RouteUnavailableException(
                                command.startStarportCode(), command.destinationStarportCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        // 5xx = infrastructure failure (planner service is down)
                        incrementErrorCounter("infrastructure");
                        throw new RouteUnavailableException(
                                command.startStarportCode(), command.destinationStarportCode());
                    })
                    .body(TradeRoutePlannerResponse.class);

            if (response == null) {
                incrementErrorCounter("empty_response");
                throw new RouteUnavailableException(command.startStarportCode(), command.destinationStarportCode());
            }

            routePlanSuccessCounter.increment();
            log.info(
                    "Route planned: {} from {} to {} — eta={}h risk={}",
                    response.routeId(),
                    command.startStarportCode(),
                    command.destinationStarportCode(),
                    response.etaHours(),
                    response.riskScore());

            return Route.builder()
                    .routeCode(response.routeId())
                    .startStarportCode(command.startStarportCode())
                    .destinationStarportCode(command.destinationStarportCode())
                    .etaHours(response.etaHours())
                    .riskScore(response.riskScore())
                    .isActive(true)
                    .build();

        } catch (RouteUnavailableException e) {
            throw e;
        } catch (Exception e) {
            incrementErrorCounter("infrastructure");
            log.error(
                    "Failed to plan route from {} to {}: {}",
                    command.startStarportCode(),
                    command.destinationStarportCode(),
                    e.getMessage(),
                    e);
            throw new RouteUnavailableException(command.startStarportCode(), command.destinationStarportCode(), e);
        }
    }

    private void incrementErrorCounter(String errorType) {
        Counter.builder(METRIC_ROUTE_PLAN_ERROR)
                .description("Number of failed route planning attempts")
                .tag("errorType", errorType)
                .register(meterRegistry)
                .increment();
    }

    private TradeRoutePlannerRequest buildRequest(ReserveBayCommand command) {
        double fuelRangeLY = fuelRangeLYForShipClass(command.shipClass());
        return new TradeRoutePlannerRequest(
                command.startStarportCode(),
                command.destinationStarportCode(),
                new TradeRoutePlannerRequest.ShipProfileDto(command.shipClass().name(), fuelRangeLY));
    }

    private double fuelRangeLYForShipClass(ReserveBayCommand.ShipClass shipClass) {
        return switch (shipClass) {
            case SCOUT -> FUEL_RANGE_SCOUT;
            case FREIGHTER -> FUEL_RANGE_FREIGHTER;
            case CRUISER -> FUEL_RANGE_CRUISER;
            case UNKNOWN -> FUEL_RANGE_UNKNOWN;
        };
    }
}
