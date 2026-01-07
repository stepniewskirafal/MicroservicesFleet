package com.galactic.router.adapters.rest;

import com.galactic.router.domain.PlanRouteCommand;
import com.galactic.router.domain.PlannedRoute;
import com.galactic.router.domain.RoutePlanningUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/routes")
public class PlanRouteController {
    private final RoutePlanningUseCase routePlanningUseCase;
    private final PlanRouteMapper mapper;

    public PlanRouteController(RoutePlanningUseCase routePlanningUseCase, PlanRouteMapper mapper) {
        this.routePlanningUseCase = routePlanningUseCase;
        this.mapper = mapper;
    }

    @PostMapping("/plan")
    public ResponseEntity<PlannedRouteResponse> planRoute(@RequestBody PlanRouteRequest request) {
        PlanRouteCommand command = mapper.toCommand(request);
        PlannedRoute plannedRoute = routePlanningUseCase.planRoute(command);
        PlannedRouteResponse response = mapper.toResponse(plannedRoute);
        return ResponseEntity.ok(response);
    }
}
