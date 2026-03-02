package com.galactic.starport.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class RestClientConfig {

    /**
     * Load-balanced builder: Spring Cloud post-processes this bean (via BeanPostProcessor)
     * to inject the LoadBalancerInterceptor before any dependent bean receives it. This
     * avoids the ordering issue of the old @LoadBalanced RestTemplate + RestClient.builder(template)
     * pattern, where interceptors were copied at build time before SmartInitializingSingleton
     * had a chance to add them.
     */
    @Bean
    @LoadBalanced
    @ConditionalOnProperty(name = "spring.cloud.discovery.enabled", havingValue = "true", matchIfMissing = true)
    RestClient.Builder loadBalancedRestClientBuilder(
            @Value("${downstream.http.connect-timeout-ms:200}") int connectTimeoutMs,
            @Value("${downstream.http.read-timeout-ms:800}") int readTimeoutMs) {
        return RestClient.builder().requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs));
    }

    /** Plain builder used when discovery is disabled (tests, local dev without Eureka). */
    @Bean
    @ConditionalOnProperty(name = "spring.cloud.discovery.enabled", havingValue = "false")
    RestClient.Builder plainRestClientBuilder(
            @Value("${downstream.http.connect-timeout-ms:200}") int connectTimeoutMs,
            @Value("${downstream.http.read-timeout-ms:800}") int readTimeoutMs) {
        return RestClient.builder().requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs));
    }

    @Bean
    RestClient tradeRoutePlannerRestClient(
            @Value("${app.trade-route-planner.base-url}") String baseUrl,
            RestClient.Builder restClientBuilder) {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
