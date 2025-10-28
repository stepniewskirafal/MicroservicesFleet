package com.galactic.starport.repository.mapper;

import com.galactic.starport.domain.Customer;
import com.galactic.starport.domain.DockingBay;
import com.galactic.starport.domain.Reservation;
import com.galactic.starport.domain.Route;
import com.galactic.starport.domain.Ship;
import com.galactic.starport.repository.CustomerEntity;
import com.galactic.starport.repository.DockingBayEntity;
import com.galactic.starport.repository.ReservationEntity;
import com.galactic.starport.repository.RouteEntity;
import com.galactic.starport.repository.ShipEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReservationMapper {

    private ReservationMapper() {}

    public static Reservation toDomain(ReservationEntity entity) {
        if (entity == null) {
            return null;
        }

        DockingBay dockingBay = DockingBayMapper.toDomain(entity.getDockingBay());
        Customer customer = CustomerMapper.toDomain(entity.getCustomer());
        Ship ship = ShipMapper.toDomain(entity.getShip(), customer);

        List<Route> routes = new ArrayList<>();
        Reservation reservation = Reservation.builder()
                .id(entity.getId())
                .dockingBay(dockingBay)
                .customer(customer)
                .ship(ship)
                .startAt(entity.getStartAt())
                .endAt(entity.getEndAt())
                .feeCharged(entity.getFeeCharged())
                .status(Optional.ofNullable(entity.getStatus())
                        .map(status -> Reservation.ReservationStatus.valueOf(status.name()))
                        .orElse(null))
                .routes(routes)
                .build();

        if (entity.getRoutes() != null) {
            entity.getRoutes().stream()
                    .map(routeEntity -> RouteMapper.toDomain(routeEntity, reservation))
                    .forEach(routes::add);
        }

        return reservation;
    }

    public static ReservationEntity toEntity(
            Reservation reservation,
            DockingBayEntity dockingBayEntity,
            CustomerEntity customerEntity,
            ShipEntity shipEntity) {
        ReservationEntity entity = new ReservationEntity();
        updateEntity(reservation, dockingBayEntity, customerEntity, shipEntity, entity);
        return entity;
    }

    public static void updateEntity(
            Reservation reservation,
            DockingBayEntity dockingBayEntity,
            CustomerEntity customerEntity,
            ShipEntity shipEntity,
            ReservationEntity entity) {
        entity.setId(reservation.getId());
        entity.setDockingBay(dockingBayEntity);
        entity.setCustomer(customerEntity);
        entity.setShip(shipEntity);
        entity.setStartAt(reservation.getStartAt());
        entity.setEndAt(reservation.getEndAt());
        entity.setFeeCharged(reservation.getFeeCharged());
        entity.setStatus(reservation.getStatus() != null
                ? ReservationEntity.ReservationStatus.valueOf(reservation.getStatus().name())
                : null);

        entity.getRoutes().clear();
        if (reservation.getRoutes() != null) {
            for (Route route : reservation.getRoutes()) {
                RouteEntity routeEntity = RouteMapper.toEntity(route);
                if (routeEntity != null) {
                    routeEntity.setReservation(entity);
                    entity.getRoutes().add(routeEntity);
                }
            }
        }
    }
}
