package com.galactic.starport.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DockingBay {

    private Long id;
    private Long starportId;
    private String bayLabel;
    private ShipClass shipClass;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public enum ShipClass {
        SCOUT,
        FREIGHTER,
        CRUISER,
        UNKNOWN
    }
}
