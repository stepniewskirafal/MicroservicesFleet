package com.galactic.starport.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
class RestClientConfig {

    @Bean("tradeRoutePlannerRestTemplate")
    @LoadBalanced
    @ConditionalOnProperty(name = "spring.cloud.discovery.enabled", havingValue = "true", matchIfMissing = true)
    RestTemplate loadBalancedRestTemplate() {
        return new RestTemplate();
    }

    @Bean("tradeRoutePlannerRestTemplate")
    @ConditionalOnProperty(name = "spring.cloud.discovery.enabled", havingValue = "false")
    RestTemplate plainRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    RestClient tradeRoutePlannerRestClient(
            @Value("${app.trade-route-planner.base-url}") String baseUrl,
            @Qualifier("tradeRoutePlannerRestTemplate") RestTemplate restTemplate) {
        return RestClient.builder(restTemplate)
                .baseUrl(baseUrl)
                .build();
    }
}
