package com.galactic.traderoute;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Machine-enforced module boundaries (ADR-0041) — the hexagonal predecessor documented its layering
 * (ADR-0021) but never enforced it. Runs in the unit phase (plain static analysis, no Spring
 * context, no Docker), so every build — including {@code -Pfast} — fails on a boundary violation:
 * a dependency cycle between modules, or a reference into another module's {@code internal} package.
 */
class ModularityTest {

    static final ApplicationModules modules = ApplicationModules.of(TradeRoutePlannerApplication.class);

    @Test
    void module_boundaries_are_respected() {
        modules.verify();
    }

    @Test
    void generates_module_documentation() {
        // PlantUML component diagrams + per-module canvases under target/spring-modulith-docs/
        new Documenter(modules).writeDocumentation();
    }
}
