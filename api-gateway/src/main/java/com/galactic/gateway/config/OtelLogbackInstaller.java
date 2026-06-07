package com.galactic.gateway.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the logback {@code OpenTelemetryAppender} (declared in logback-spring.xml) to the
 * autoconfigured OpenTelemetry SDK so application logs are actually exported via OTLP (ADR-0035).
 *
 * <p>Spring Boot 3.5 does NOT auto-install the appender from classpath presence alone — without this
 * call the appender stays attached to a no-op OpenTelemetry instance and silently drops every record,
 * so nothing reaches Alloy. The constructor runs during context refresh; logs emitted afterwards
 * (including all request-scoped logs) are captured and pushed.
 */
@Configuration
public class OtelLogbackInstaller {

    public OtelLogbackInstaller(OpenTelemetry openTelemetry) {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
