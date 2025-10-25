package com.galactic.starport.service;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Route {
    private Long id;
    private Reservation reservation;
    private Double etaLY;
    private Double riskScore;
    private boolean isActive;
}
