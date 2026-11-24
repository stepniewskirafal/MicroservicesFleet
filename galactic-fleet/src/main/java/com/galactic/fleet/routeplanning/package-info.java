/**
 * Module {@code routeplanning}: plans a route between two ports for a ship class — fuel-range
 * check, corridor risk, ETA. Today a pure computation; the module exists as the home for richer
 * route calculations (ADR-0043).
 *
 * <p>One named interface, {@code api} ({@link com.galactic.fleet.routeplanning.api.PlanRouteUseCase}
 * with its request, result and rejection types). The module publishes no events and performs no I/O.
 * It depends on no other module: the empty {@code allowedDependencies} makes any such import a build
 * failure (ADR-0043).
 */
@ApplicationModule(
        displayName = "Route planning",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {})
package com.galactic.fleet.routeplanning;

import org.springframework.modulith.ApplicationModule;
