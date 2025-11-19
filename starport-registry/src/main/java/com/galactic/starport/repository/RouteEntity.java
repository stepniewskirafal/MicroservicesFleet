package com.galactic.starport.repository;

import com.galactic.starport.service.Route;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "route")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
class RouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "route_id_seq_gen")
    @SequenceGenerator(name = "route_id_seq_gen", sequenceName = "route_id_seq", allocationSize = 10)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private ReservationEntity reservation;

    @Column(name = "route_code")
    private String routeCode;

    @Column(name = "start_port_code")
    private String startStarportCode;

    @Column(name = "destination_port_code")
    private String destinationStarportCode;

    @Column(name = "eta_light_years")
    private Double etaLightYears;

    @Column(name = "risk_score")
    private Double riskScore;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public RouteEntity(Route route, ReservationEntity reservation) {
        this.id = route.getId();
        this.reservation = reservation;
        this.routeCode = route.getRouteCode();
        this.startStarportCode = route.getStartStarportCode();
        this.destinationStarportCode = route.getDestinationStarportCode();
        this.etaLightYears = route.getEtaLightYears();
        this.riskScore = route.getRiskScore();
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
