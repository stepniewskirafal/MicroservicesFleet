package com.galactic.traderoute.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Execution(ExecutionMode.CONCURRENT)
class RouteRejectionExceptionTest {

    @Test
    void should_store_reason_and_details() {
        var ex = new RouteRejectionException("INSUFFICIENT_RANGE", "Not enough fuel");

        assertThat(ex.getReason()).isEqualTo("INSUFFICIENT_RANGE");
        assertThat(ex.getDetails()).isEqualTo("Not enough fuel");
    }

    @Test
    void should_include_reason_and_details_in_message() {
        var ex = new RouteRejectionException("BLOCKED_ROUTE", "Hyperlane occupied");

        assertThat(ex.getMessage()).contains("BLOCKED_ROUTE").contains("Hyperlane occupied");
    }

    @Test
    void should_be_a_runtime_exception() {
        var ex = new RouteRejectionException("R", "D");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest(name = "reason={0}")
    @CsvSource({
        "INSUFFICIENT_RANGE, Fuel too low",
        "BLOCKED_ROUTE,      Hyperlane blocked",
        "UNKNOWN_PORT,       Port not in registry"
    })
    void should_preserve_arbitrary_reasons(String reason, String details) {
        var ex = new RouteRejectionException(reason, details);

        assertThat(ex.getReason()).isEqualTo(reason);
        assertThat(ex.getDetails()).isEqualTo(details);
        assertThat(ex.getMessage()).contains(reason).contains(details);
    }
}
