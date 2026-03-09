package com.galactic.telemetry.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SensorThresholdProperties.class)
public class TelemetryPropertiesAutoConfiguration {}
