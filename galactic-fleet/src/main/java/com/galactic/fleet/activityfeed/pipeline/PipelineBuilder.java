package com.galactic.fleet.activityfeed.pipeline;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Function;

/**
 * Composes filters into one function — the "pipes" of Pipes &amp; Filters (ADR-0022, ADR-0043).
 *
 * <p>Every stage gets a timer ({@code events.filter.<stage>}). A stage that returns {@code null}
 * ends the chain: the stages after it are skipped and the pipeline returns {@code null}, which the
 * caller reads as "nothing to publish".
 *
 * <p><b>A stage that throws is not caught here.</b> The pipeline runs inside an {@code
 * @ApplicationModuleListener}, and the Event Publication Registry completes a publication only when
 * the listener returns normally. Swallowing the exception would mark the publication complete and
 * lose the event; letting it propagate keeps the row in {@code event_publication} for re-delivery
 * (ADR-0043).
 *
 * @param <I> input of the whole pipeline
 * @param <O> output of the stages added so far
 */
public final class PipelineBuilder<I, O> {

    private static final String TIMER_PREFIX = "events.filter.";

    private final MeterRegistry meterRegistry;
    private final Function<I, O> composed;

    private PipelineBuilder(MeterRegistry meterRegistry, Function<I, O> composed) {
        this.meterRegistry = meterRegistry;
        this.composed = composed;
    }

    public static <T> PipelineBuilder<T, T> create(MeterRegistry meterRegistry) {
        return new PipelineBuilder<>(meterRegistry, Function.identity());
    }

    public <N> PipelineBuilder<I, N> stage(String name, Function<O, N> filter) {
        Timer timer = Timer.builder(TIMER_PREFIX + name)
                .description("Time spent in one filter of the activity-feed pipeline")
                .register(meterRegistry);
        Function<O, N> instrumented = input -> {
            if (input == null) {
                return null;
            }
            return timer.record(() -> filter.apply(input));
        };
        return new PipelineBuilder<>(meterRegistry, composed.andThen(instrumented));
    }

    public Function<I, O> build() {
        return composed;
    }
}
