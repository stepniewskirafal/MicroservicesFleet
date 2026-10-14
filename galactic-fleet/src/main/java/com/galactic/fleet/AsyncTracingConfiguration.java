package com.galactic.fleet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * Carries the trace context onto the threads that run {@code @ApplicationModuleListener}s.
 *
 * <p>Those listeners run after the publisher's commit on Spring's async executor (ADR-0046). Without
 * this decorator the new thread starts with an empty context, so everything a listener emits —
 * including the {@code traceparent} of the Kafka records it produces — would begin a new trace
 * instead of continuing the trace of the HTTP request that confirmed the reservation (ADR-0017).
 * Spring Boot applies a {@link TaskDecorator} bean to the auto-configured executor.
 *
 * <p>Platform configuration, not a module: it sits in the application's root package.
 */
@Configuration(proxyBeanMethods = false)
class AsyncTracingConfiguration {

    @Bean
    TaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}
