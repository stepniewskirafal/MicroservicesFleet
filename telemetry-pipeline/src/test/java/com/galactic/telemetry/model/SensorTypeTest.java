package com.galactic.telemetry.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Execution(ExecutionMode.CONCURRENT)
class SensorTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"TEMPERATURE", "RADIATION", "FUEL_LEVEL", "ENGINE_VIBRATION", "HULL_INTEGRITY", "OXYGEN_LEVEL"})
    void isValid_should_return_true_for_all_known_types(String type) {
        assertThat(SensorType.isValid(type)).isTrue();
    }

    @Test
    void isValid_should_return_false_for_null() {
        assertThat(SensorType.isValid(null)).isFalse();
    }

    @Test
    void isValid_should_return_false_for_empty_string() {
        assertThat(SensorType.isValid("")).isFalse();
    }

    @Test
    void isValid_should_return_false_for_lowercase() {
        assertThat(SensorType.isValid("temperature")).isFalse();
    }

    @Test
    void isValid_should_return_false_for_unknown_type() {
        assertThat(SensorType.isValid("WARP_DRIVE")).isFalse();
    }

    @Test
    void isValid_should_return_false_for_whitespace() {
        assertThat(SensorType.isValid(" TEMPERATURE ")).isFalse();
    }

    @Test
    void enum_should_have_six_values() {
        assertThat(SensorType.values()).hasSize(6);
    }
}
