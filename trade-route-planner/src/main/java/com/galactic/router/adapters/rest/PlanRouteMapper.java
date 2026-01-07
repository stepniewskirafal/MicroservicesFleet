package com.galactic.router.adapters.rest;

import com.galactic.router.domain.PlanRouteCommand;
import com.galactic.router.domain.PlannedRoute;
import com.galactic.router.domain.ShipProfile;

public class PlanRouteMapper {

    public PlanRouteCommand toCommand(PlanRouteRequest request) {
        ShipProfileDto dto = request.getShipProfile();
        ShipProfile profile = dto != null
                ? new ShipProfile(dto.getShipClass(), dto.getFuelRangeLY())
                : null;
        return new PlanRouteCommand(request.getOriginPortId(), request.getDestinationPortId(), profile);
    }

    public PlannedRouteResponse toResponse(PlannedRoute route) {
        return new PlannedRouteResponse(route.getRouteId(), route.getEtaHours(), route.getRiskScore());
    }
}
