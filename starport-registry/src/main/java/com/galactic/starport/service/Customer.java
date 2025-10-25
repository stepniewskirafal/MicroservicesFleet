package com.galactic.starport.service;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class Customer {
    private Long id;
    private String customerCode;
    private String name;
    private Instant createdAt;
    private Instant updatedAt;
}
