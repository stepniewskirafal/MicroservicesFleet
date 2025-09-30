package com.galactic.starport.service;

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
    private List<Ship> ships;

    private Instant createdAt;
    private Instant updatedAt;
}
