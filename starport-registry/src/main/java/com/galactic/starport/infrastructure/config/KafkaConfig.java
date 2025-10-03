package com.galactic.starport.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TopicsProperties.class)
public class KafkaConfig {
    // KISS: KafkaTemplate dostarcza starter spring-kafka z autokonfiguracji.
}
