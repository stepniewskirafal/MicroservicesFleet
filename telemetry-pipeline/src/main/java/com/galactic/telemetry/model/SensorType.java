package com.galactic.telemetry.model;

public enum SensorType {
    TEMPERATURE,
    RADIATION,
    FUEL_LEVEL,
    ENGINE_VIBRATION,
    HULL_INTEGRITY,
    OXYGEN_LEVEL;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        try {
            valueOf(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
