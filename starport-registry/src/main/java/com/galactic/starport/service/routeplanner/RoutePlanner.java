package com.galactic.starport.service.routeplanner;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;

// Public: Single access point for route planning functionality
public interface RoutePlanner {
    Route calculateRoute(ReserveBayCommand command);
}
