package com.galactic.starport.repository;

import java.util.List;

public interface DockOccupancyQuery {

    List<DockOccupancySnapshot> aggregate();

    record DockOccupancySnapshot(String starportCode, long occupied, long total) {}
}
