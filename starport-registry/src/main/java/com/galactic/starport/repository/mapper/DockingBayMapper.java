package com.galactic.starport.repository.mapper;

import com.galactic.starport.domain.DockingBay;
import com.galactic.starport.repository.DockingBayEntity;

public final class DockingBayMapper {

    private DockingBayMapper() {}

    public static DockingBay toDomain(DockingBayEntity entity) {
        if (entity == null) {
            return null;
        }

        return DockingBay.builder()
                .id(entity.getId())
                .starportId(entity.getStarport() != null ? entity.getStarport().getId() : null)
                .bayLabel(entity.getBayLabel())
                .shipClass(entity.getShipClass() != null
                        ? DockingBay.ShipClass.valueOf(entity.getShipClass().name())
                        : null)
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
