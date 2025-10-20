package com.galactic.starport.service;

import java.time.Instant;
import java.util.List;

import com.galactic.starport.repository.ShipEntity;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class Customer {
    private Long id;
    private String customerCode;
    private String name;
    private List<ShipEntity> ships;
    private Instant createdAt;
    private Instant updatedAt;
}
