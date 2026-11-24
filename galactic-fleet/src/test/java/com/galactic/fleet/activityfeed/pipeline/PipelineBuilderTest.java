package com.galactic.fleet.activityfeed.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        Function<Integer, Integer> pipeline = PipelineBuilder.<Integer>create(registry)
                .stage("inc", i -> i + 1)
                .build();

        assertThat(pipeline.apply(1)).isEqualTo(2);
        assertThat(registry.get("events.filter.inc").timer().count()).isEqualTo(1);
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
        assertThat(registry.get("events.filter.downstream").timer().count()).isZero();
    }

    @Test
    void throwing_stage_propagates_so_the_event_publication_stays_incomplete() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        IllegalStateException poison = new IllegalStateException("poison");

        Function<Integer, String> pipeline = PipelineBuilder.<Integer>create(registry)
                .<Integer>stage("boom", i -> {
                    throw poison;
                })
                .stage("downstream", i -> {
                    downstreamCalled.set(true);
                    return "reached";
                })
                .build();

        // The pipeline runs inside an @ApplicationModuleListener. The Event Publication Registry
        // completes a publication when the listener returns normally, so a swallowed exception would
        // lose the event; propagating it leaves the row for re-delivery (ADR-0043).
        assertThatThrownBy(() -> pipeline.apply(1)).isSameAs(poison);
        assertThat(downstreamCalled)
                .as("downstream must not run after an upstream failure")
                .isFalse();
    }
}
