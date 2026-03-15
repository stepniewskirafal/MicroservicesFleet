package com.galactic.starport.service;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Route {
    private Long id;
    private String routeCode;
    private String startStarportCode;
    private String destinationStarportCode;
    private Double etaHours;
    private Double riskScore;
    private boolean isActive;
}
