package com.galactic.starport.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Ship {
    private Long id;
    private Customer customer;
    private String shipCode;
    private String shipName;
    private ShipClass shipClass;
    private Instant createdAt;
    private Instant updatedAt;

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
