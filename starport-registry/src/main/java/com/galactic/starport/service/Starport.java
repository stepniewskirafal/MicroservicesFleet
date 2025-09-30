package com.galactic.starport.service;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Starport {
    private Long id;
    private String code;
    private String name;
    private String description;

    @Builder.Default
    private List<DockingBay> dockingBays = new ArrayList<>();
}
