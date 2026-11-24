package com.galactic.fleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The whole fleet in one process (ADR-0043). Every direct subpackage is a Spring
 * Modulith module: {@code booking}, {@code routeplanning}, {@code activityfeed}. A module exposes
 * only its named interfaces ({@code api} / {@code events} subpackages); every other subpackage is
 * internal. The boundaries are enforced by {@code ModularityTest}.
 *
 * <p>Classes in this root package belong to no module: they are platform configuration (tracing,
 * logging) that no business module depends on.
 */
@SpringBootApplication
@EnableScheduling
public class GalacticFleetApplication {

    public static void main(String[] args) {
        SpringApplication.run(GalacticFleetApplication.class, args);
    }
}
