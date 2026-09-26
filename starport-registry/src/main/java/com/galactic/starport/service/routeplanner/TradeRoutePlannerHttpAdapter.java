package com.galactic.starport.service.routeplanner;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Domain-facing adapter for the {@link RoutePlanner} port: validation, the "no route wanted"
 * short-circuit, observability and domain-to-DTO mapping.
 *
 * <p>Deliberately carries NO resilience annotations. They live one level down, in
 * {@link TradeRoutePlannerClient}, so the circuit breaker observes the wire and nothing else —
 * see the class javadoc there for what that fixes.
 *
 * <p>Returns {@code null} when the caller did not ask for a route. That is not an oversight:
 * {@code Route} is nullable all the way down to the entity ({@code reservation.getRoute()}), and
 * every consumer already guards with {@code Optional.ofNullable} or an explicit null check.
 * Making only this one boundary {@code Optional} would move the inconsistency rather than remove
 * it; doing it properly means threading {@code Optional} through the calculation record, the
 * confirmation service and the mappers. Tracked in ADR-0039's backlog.
 */
@Service
@Slf4j
class TradeRoutePlannerHttpAdapter implements RoutePlanner {

    private static final String OBSERVATION_NAME = "reservations.route.plan";
    private static final double FUEL_RANGE_SCOUT = 15.0;
    private static final double FUEL_RANGE_FREIGHTER = 25.0;
    private static final double FUEL_RANGE_CRUISER = 40.0;
    private static final double FUEL_RANGE_UNKNOWN = 5.0;

    private final TradeRoutePlannerClient client;
    private final TradeRoutePlannerResponseMapper mapper;
    private final ObservationRegistry observationRegistry;

    TradeRoutePlannerHttpAdapter(
            TradeRoutePlannerClient client,
            TradeRoutePlannerResponseMapper mapper,
            ObservationRegistry observationRegistry) {
        this.client = client;
        this.mapper = mapper;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public Route calculateRoute(ReserveBayCommand command) {
        // Both of these run OUTSIDE the resilience proxy on purpose: a null command is our bug,
        // and "no route requested" never touches the planner. Neither may reach the breaker.
        Objects.requireNonNull(command, "command must not be null");
        if (!command.requestRoute()) {
            return null;
        }

        String origin = command.startStarportCode();
        String destination = command.destinationStarportCode();
        TradeRoutePlannerRequest request = buildRequest(command);

        // The observation wraps the WHOLE operation (every retry attempt plus the mapping), the
        // resilience proxy wraps only client.plan(). Mapping the DTO here, not in the client, keeps
        // a mapping bug out of the breaker's window: the planner answered, so as far as the breaker
        // can tell the dependency is fine — the bug is ours and leaves with its own type.
        return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("startStarport", Objects.toString(origin, "unknown"))
                .lowCardinalityKeyValue("destinationStarport", destination)
                .observe(() -> mapper.toRoute(client.plan(request, origin, destination), origin, destination));
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
