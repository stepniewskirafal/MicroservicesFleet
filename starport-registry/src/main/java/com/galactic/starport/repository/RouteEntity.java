package com.galactic.starport.repository;

import com.galactic.starport.service.Route;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "route")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
class RouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "route_id_seq_gen")
    @SequenceGenerator(name = "route_id_seq_gen", sequenceName = "route_id_seq", allocationSize = 10)
    private Long id;

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

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public RouteEntity(Route route, Long reservationId) {
        this.id = route.getId();
        this.routeCode = route.getRouteCode();
        this.startStarportCode = route.getStartStarportCode();
        this.destinationStarportCode = route.getDestinationStarportCode();
        this.etaLightYears = route.getEtaLightYears();
        this.riskScore = route.getRiskScore();
        this.reservationId = reservationId;
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
