package com.galactic.starport.repository;

import com.galactic.starport.service.Starport;

final class StarportMapper {

    private StarportMapper() {}

    static Starport toDomain(StarportEntity entity) {
        if (entity == null) {
            return null;
        }

        return Starport.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                // jeśli w domenie masz listę DockingBay w Starporcie,
                // lepiej ładować je osobno, a nie przez relację JPA
                .build();
    }

    // Świadomie nie implementuję pełnego mapowania domena -> encja,
    // bo StarportEntity nie ma konstruktora ani setterów bazujących na domenie.
    // Jeśli będziesz potrzebował, najprościej dodać:
    // - public StarportEntity(Starport starport)
    // - lub @Setter na wybranych polach
    // i dodać tutaj metody toEntity/updateEntity.
}
