package com.galactic.starport.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class RestClientConfig {

    // RestClientBuilderConfigurer applies Boot's RestClientCustomizers — crucially the Micrometer
    // ObservationRestClientCustomizer, which creates the client span and injects the W3C
    // `traceparent` header on outbound calls. Building from a bare RestClient.builder() (the static
    // factory) bypasses all customizers, so the call to trade-route-planner would be untraced and
    // the planner would start a disconnected root trace. ADR-0017.

    @Bean
    @LoadBalanced
    @ConditionalOnProperty(name = "spring.cloud.discovery.enabled", havingValue = "true", matchIfMissing = true)
    RestClient.Builder loadBalancedRestClientBuilder(
            RestClientBuilderConfigurer configurer,
            @Value("${downstream.http.connect-timeout-ms:200}") int connectTimeoutMs,
            @Value("${downstream.http.read-timeout-ms:800}") int readTimeoutMs) {
        return configurer.configure(RestClient.builder()).requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs));
    }

    /** Plain builder used when discovery is disabled (tests, local dev without Eureka). */
    @Bean
    @ConditionalOnProperty(name = "spring.cloud.discovery.enabled", havingValue = "false")
    RestClient.Builder plainRestClientBuilder(
            RestClientBuilderConfigurer configurer,
            @Value("${downstream.http.connect-timeout-ms:200}") int connectTimeoutMs,
            @Value("${downstream.http.read-timeout-ms:800}") int readTimeoutMs) {
        return configurer.configure(RestClient.builder()).requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs));
    }

    // The planner client normally rides the @LoadBalanced builder, resolving lb://trade-route-planner
    // through Eureka. The demo profile flips `app.trade-route-planner.load-balanced=false` to point the
    // SAME client at a fixed host:port (Toxiproxy) WITHOUT disabling discovery globally — starport must
    // keep registering with Eureka so the gateway can still route to it. We build a non-LB client from
    // the configurer (not RestClient.builder()) so Boot's Micrometer customizers still create the client
    // span + inject `traceparent` (ADR-0017); only the load-balancing interceptor is dropped.
    @Bean
    RestClient tradeRoutePlannerRestClient(
            @Value("${app.trade-route-planner.base-url}") String baseUrl,
            @Value("${app.trade-route-planner.load-balanced:true}") boolean loadBalanced,
            RestClient.Builder loadBalancedRestClientBuilder,
            RestClientBuilderConfigurer configurer,
            @Value("${downstream.http.connect-timeout-ms:200}") int connectTimeoutMs,
            @Value("${downstream.http.read-timeout-ms:800}") int readTimeoutMs) {
        RestClient.Builder builder = loadBalanced
                ? loadBalancedRestClientBuilder
                : configurer.configure(RestClient.builder())
                        .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs));
        return builder.baseUrl(baseUrl).build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
