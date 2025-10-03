package com.galactic.starport.domain.model;

import com.galactic.starport.domain.enums.DockingBayStatus;
import com.galactic.starport.domain.enums.ShipClass;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
        name = "docking_bay",
        indexes = {
            @Index(name = "ix_docking_bay_starport", columnList = "starport_id"),
            @Index(name = "ix_docking_bay_ship_class", columnList = "ship_class")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DockingBay {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "starport_id", nullable = false)
    private Starport starport;

    @Enumerated(EnumType.STRING)
    @Column(name = "ship_class", nullable = false, length = 32)
    private ShipClass shipClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private DockingBayStatus status = DockingBayStatus.ACTIVE;
}
