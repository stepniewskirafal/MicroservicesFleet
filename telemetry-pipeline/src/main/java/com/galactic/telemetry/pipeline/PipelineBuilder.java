package com.galactic.telemetry.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Type-safe builder that composes pipe-and-filter stages into a single {@link Function}, applying
 * three cross-cutting concerns uniformly so individual filters stay pure:
 *
 * <ol>
 *   <li><b>Timing</b> — each stage is wrapped in a {@code telemetry.filter.<name>} timer, derived
 *       from the stage name. Adding a filter needs no new {@code Timer}.
 *   <li><b>Null short-circuit</b> — a stage that returns {@code null} ends the pipeline (the message
 *       is filtered out); downstream stages are skipped, not invoked with {@code null}.
 *   <li><b>Error isolation</b> — a stage that throws is logged at ERROR and counted
 *       ({@code telemetry.filter.errors{stage}}); the offending message is dropped <em>here</em>
 *       rather than propagated to the broker, where (with {@code max-attempts} exhausted and no DLQ)
 *       it would be retried then silently lost. One poison message can no longer stall or kill the
 *       stream, and the drop is observable instead of a quiet WARN.
 * </ol>
 *
 * <p>Open/Closed: extension is a single {@code .stage(...)} call. The generic parameter {@code O}
 * carries the running output type, so the compiler still enforces stage ordering — a stage's input
 * type must equal the previous stage's output type, preserving the {@code Raw → Validated →
 * Enriched → Aggregated → Alert} compile-time chain.
 */
public final class PipelineBuilder<I, O> {

    private static final Logger log = LoggerFactory.getLogger(PipelineBuilder.class);
    private static final String TIMER_PREFIX = "telemetry.filter.";
    private static final String ERROR_METRIC = "telemetry.filter.errors";

    private final MeterRegistry meterRegistry;
    private final Function<I, O> composed;

    private PipelineBuilder(MeterRegistry meterRegistry, Function<I, O> composed) {
        this.meterRegistry = meterRegistry;
        this.composed = composed;
    }

    /** Start a pipeline whose input and (current) output type are both {@code T}. */
    public static <T> PipelineBuilder<T, T> create(MeterRegistry meterRegistry) {
        return new PipelineBuilder<>(meterRegistry, Function.identity());
    }

    /**
     * Append a filter. {@code name} drives the per-stage timer ({@code telemetry.filter.<name>}) and
     * the error counter tag, so each stage is instrumented without bespoke code.
     */
    public <N> PipelineBuilder<I, N> stage(String name, Function<O, N> filter) {
        Timer timer = Timer.builder(TIMER_PREFIX + name).register(meterRegistry);
        Function<O, N> instrumented = input -> {
            if (input == null) {
                // An upstream stage filtered this message out — short-circuit the rest.
                return null;
            }
            try {
                return timer.record(() -> filter.apply(input));
            } catch (RuntimeException ex) {
                Counter.builder(ERROR_METRIC)
                        .tag("stage", name)
                        .description("Messages dropped because a filter stage threw")
                        .register(meterRegistry)
                        .increment();
                log.error("Filter stage '{}' failed — dropping message", name, ex);
                return null;
            }
        };
        return new PipelineBuilder<>(meterRegistry, composed.andThen(instrumented));
    }

    /** The composed, fully instrumented pipeline function. */
    public Function<I, O> build() {
        return composed;
    }
}
