package com.galactic.starport.service;

import lombok.Builder;
import lombok.Getter;
import java.util.ArrayList;
import java.util.List;

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
