package com.galactic.traderoute.adapter.in.rest;

import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRequest;
import com.galactic.traderoute.port.in.PlanRouteUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
@Slf4j
class RoutePlannerController {

    private final PlanRouteUseCase planRouteUseCase;

    @PostMapping("/plan")
    ResponseEntity<PlanRouteResponse> plan(@Valid @RequestBody PlanRouteRequest request) {
        log.info(
                "Planning route from {} to {} for ship class {}",
                request.originPortId(),
                request.destinationPortId(),
                request.shipProfile().shipClass());

        RouteRequest domainRequest = RouteRequest.builder()
                .originPortId(request.originPortId())
                .destinationPortId(request.destinationPortId())
                .shipClass(request.shipProfile().shipClass())
                .fuelRangeLY(request.shipProfile().fuelRangeLY())
                .build();

        PlannedRoute planned = planRouteUseCase.planRoute(domainRequest);

        PlanRouteResponse response = PlanRouteResponse.builder()
                .routeId(planned.routeId())
                .etaHours(planned.etaHours())
                .riskScore(planned.riskScore())
                .correlationId(UUID.randomUUID().toString())
                .build();

        return ResponseEntity.ok(response);
    }
}
