package com.galactic.traderoute.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

@Builder
public record PlanRouteRequest(
        @NotBlank String originPortId, @NotBlank String destinationPortId, @NotNull @Valid ShipProfileDto shipProfile) {

    public record ShipProfileDto(@NotBlank @JsonProperty("class") String shipClass, @Positive double fuelRangeLY) {}
}
