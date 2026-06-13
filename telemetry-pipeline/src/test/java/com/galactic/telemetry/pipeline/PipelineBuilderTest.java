package com.galactic.telemetry.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class PipelineBuilderTest {

    @Test
    void composes_stages_in_declared_order() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Function<Integer, String> pipeline = PipelineBuilder.<Integer>create(registry)
                .stage("double", i -> i * 2)
                .stage("stringify", i -> "v=" + i)
                .build();

        assertThat(pipeline.apply(21)).isEqualTo("v=42");
    }

    @Test
    void records_a_timer_per_stage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Function<Integer, Integer> pipeline =
                PipelineBuilder.<Integer>create(registry).stage("inc", i -> i + 1).build();

        assertThat(pipeline.apply(1)).isEqualTo(2);
        assertThat(registry.get("telemetry.filter.inc").timer().count()).isEqualTo(1);
    }

    @Test
    void null_from_a_stage_short_circuits_downstream() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);

        Function<Integer, String> pipeline = PipelineBuilder.<Integer>create(registry)
                .<Integer>stage("nullify", i -> null)
                .stage("downstream", i -> {
                    downstreamCalled.set(true);
                    return "reached";
                })
                .build();

        assertThat(pipeline.apply(1)).isNull();
        assertThat(downstreamCalled).isFalse();
    }

    @Test
    void throwing_stage_is_isolated_dropped_and_counted_not_propagated() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);

        Function<Integer, String> pipeline = PipelineBuilder.<Integer>create(registry)
                .<Integer>stage("boom", i -> {
                    throw new IllegalStateException("poison");
                })
                .stage("downstream", i -> {
                    downstreamCalled.set(true);
                    return "reached";
                })
                .build();

        // The exception must NOT propagate to the caller — otherwise it reaches Spring Cloud Stream,
        // is retried max-attempts times, and the message is silently lost. It is dropped here instead.
        assertThat(pipeline.apply(1)).isNull();
        assertThat(downstreamCalled).as("downstream must be skipped after an upstream failure").isFalse();
        assertThat(registry.get("telemetry.filter.errors").tag("stage", "boom").counter().count())
                .isEqualTo(1.0);
    }
}
