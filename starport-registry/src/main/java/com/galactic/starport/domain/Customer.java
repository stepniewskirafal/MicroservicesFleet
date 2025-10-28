package com.galactic.starport.domain;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Builder(toBuilder = true)
@Getter
public class Customer {
    private Long id;
    private String customerCode;
    private String name;

    @Builder.Default
    private List<Ship> ships = List.of();

    private Instant createdAt;
    private Instant updatedAt;
}
