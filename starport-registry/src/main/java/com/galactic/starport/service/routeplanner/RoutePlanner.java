package com.galactic.starport.service.routeplanner;

import com.galactic.starport.service.ReserveBayCommand;
import com.galactic.starport.service.Route;

public interface RoutePlanner {
    Route calculateRoute(ReserveBayCommand command);
}
