package com.galactic.starport.repository;

import com.galactic.starport.service.DockingBay;

final class DockingBayMapper {

    private DockingBayMapper() {}

    static DockingBay toDomain(DockingBayEntity entity) {
        if (entity == null) {
            return null;
        }
        return entity.toModel();
    }

    /**
     * Tworzy nową encję DockingBayEntity na podstawie domenowego DockingBay
     * i już załadowanego StarportEntity.
     */
    static DockingBayEntity toEntity(DockingBay dockingBay, StarportEntity starportEntity) {
        if (dockingBay == null) {
            return null;
        }
        return new DockingBayEntity(starportEntity, dockingBay);
    }
}
