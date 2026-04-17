package com.galactic.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Single public ingress for the fleet.
 *
 * <p>Routes traffic to backend services via Eureka-resolved {@code lb://} URIs —
 * no hard-coded instance hostnames or ports. Load balancing is handled by
 * Spring Cloud LoadBalancer using the live Eureka registry (ADR-0003).
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
