package com.galactic.starport.repository;

import com.galactic.starport.service.Customer;
import com.galactic.starport.service.DockingBay;
import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.Ship;
import com.galactic.starport.service.Starport;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReservationMapper {

    Reservation toDomain(ReservationEntity entity) {
        Objects.requireNonNull(entity, "reservationEntity must not be null");

        final Customer customer = mapCustomer(entity.getCustomer());
        final Ship ship = mapShip(entity.getShip(), customer);

        return Reservation.builder()
                .id(entity.getId())
                .starport(mapStarport(entity.getStarportEntity()))
                .dockingBay(mapDockingBay(entity.getDockingBay()))
                .customer(customer)
                .ship(ship)
                .startAt(entity.getStartAt())
                .endAt(entity.getEndAt())
                .feeCharged(entity.getFeeCharged())
                .status(mapStatus(entity.getStatus()))
                .route(mapRoute(entity.getRoute(), entity.getStatus()))
                .build();
    }

    private Customer mapCustomer(CustomerEntity customerEntity) {
        if (customerEntity == null) {
            return null;
        }

        return Customer.builder()
                .id(customerEntity.getId())
                .customerCode(customerEntity.getCustomerCode())
                .name(customerEntity.getName())
                .ships(List.of())
                .createdAt(customerEntity.getCreatedAt())
                .updatedAt(customerEntity.getUpdatedAt())
                .build();
    }

    private Ship mapShip(ShipEntity shipEntity, Customer customer) {
        if (shipEntity == null) {
            return null;
        }
        return Ship.builder()
                .id(shipEntity.getId())
                .customer(customer)
                .shipCode(shipEntity.getShipCode())
                .shipName(null)
                .shipClass(
                        shipEntity.getShipClass() == null
                                ? Ship.ShipClass.UNKNOWN
                                : Ship.ShipClass.valueOf(
                                        shipEntity.getShipClass().name()))
                .createdAt(shipEntity.getCreatedAt())
                .updatedAt(shipEntity.getUpdatedAt())
                .build();
    }

    private DockingBay mapDockingBay(DockingBayEntity dockingBayEntity) {
        return dockingBayEntity == null ? null : dockingBayEntity.toModel();
    }

    private Starport mapStarport(StarportEntity starportEntity) {
        return starportEntity == null ? null : starportEntity.toModel();
    }

    private Reservation.ReservationStatus mapStatus(ReservationEntity.ReservationStatus status) {
        return status == null ? null : Reservation.ReservationStatus.valueOf(status.name());
    }

    private Route mapRoute(RouteEntity routeEntity, ReservationEntity.ReservationStatus reservationStatus) {
        if (routeEntity == null) {
            return null;
        }

        return Route.builder()
                .id(routeEntity.getId())
                .routeCode(routeEntity.getRouteCode())
                .startStarportCode(routeEntity.getStartStarportCode())
                .destinationStarportCode(routeEntity.getDestinationStarportCode())
                .etaLightYears(routeEntity.getEtaLightYears())
                .riskScore(routeEntity.getRiskScore())
                .isActive(reservationStatus == ReservationEntity.ReservationStatus.CONFIRMED)
                .build();
    }
}
