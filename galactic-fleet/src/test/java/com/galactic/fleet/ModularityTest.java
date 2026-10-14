package com.galactic.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.fleet.booking.events.ReservationConfirmed;
import com.galactic.fleet.routeplanning.api.PlanRouteUseCase;
import com.galactic.fleet.routeplanning.api.PlannedRoute;
import com.galactic.fleet.routeplanning.api.RouteRejectionException;
import com.galactic.fleet.routeplanning.api.RouteRequest;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;
import org.springframework.modulith.docs.Documenter;

/**
 * Machine-enforced module boundaries (ADR-0043, ADR-0046). Runs in the unit phase (plain static
 * analysis, no Spring context, no Docker), so every build — including {@code -Pfast} — fails on a
 * boundary violation: a dependency cycle between modules, a reference into another module's
 * internals, or a dependency that is not listed in {@code allowedDependencies}.
 */
class ModularityTest {

    static final ApplicationModules modules = ApplicationModules.of(GalacticFleetApplication.class);

    @Test
    void module_boundaries_are_respected() {
        modules.verify();
    }

    /**
     * ADR-0044: {@code verify()} enforces {@code allowedDependencies}, but it cannot tell whether a
     * type sits in the right named interface. Dropping {@code @NamedInterface} from an event, or
     * tagging it {@code api}, would otherwise only show up as a confusing violation elsewhere.
     */
    @Test
    void named_interfaces_expose_exactly_the_intended_types() {
        assertThat(typesOf("routeplanning", "api"))
                .containsExactlyInAnyOrder(
                        PlanRouteUseCase.class, RouteRequest.class, PlannedRoute.class, RouteRejectionException.class);
        assertThat(typesOf("booking", "events")).containsExactly(ReservationConfirmed.class);
    }

    /**
     * ADR-0046: route planning is a pure computation behind one synchronous call. An {@code events}
     * named interface reappearing there would mean the module started announcing things again —
     * and with it the question of who delivers them, on which thread, in whose transaction.
     */
    @Test
    void routeplanning_exposes_an_api_and_nothing_else() {
        assertThat(modules.getModuleByName("routeplanning")
                        .orElseThrow()
                        .getNamedInterfaces()
                        .getByName("events"))
                .isEmpty();
    }

    private static List<Class<?>> typesOf(String module, String namedInterface) {
        NamedInterface found = modules.getModuleByName(module)
                .orElseThrow()
                .getNamedInterfaces()
                .getByName(namedInterface)
                .orElseThrow(
                        () -> new AssertionError("no named interface '" + namedInterface + "' in module " + module));
        // the package also holds nested types (builders, nested records) and package-info — irrelevant here
        return found.asJavaClasses()
                .filter(type -> type.isTopLevelClass() && !type.getSimpleName().equals("package-info"))
                .<Class<?>>map(JavaClass::reflect)
                .toList();
    }

    /**
     * ADR-0045: a module's provided interface is its public beans <em>and</em> the events it
     * publishes. Spring Modulith only knows an event is "published" when its type is a jMolecules
     * {@code @DomainEvent}; without the annotation everything still works, but the generated module
     * canvases silently lose their "Published events" row.
     */
    @Test
    void modules_declare_the_events_they_publish() {
        assertThat(publishedEventsOf("booking")).containsExactly(ReservationConfirmed.class);
        assertThat(publishedEventsOf("routeplanning")).isEmpty();
        assertThat(publishedEventsOf("shipmonitoring")).isEmpty();
        assertThat(publishedEventsOf("activityfeed")).isEmpty();
    }

    /**
     * ADR-0045: every module is {@code Type.CLOSED}, stated explicitly in its package-info. An
     * {@code OPEN} module exposes its internal packages — a migration aid, not a design we want.
     */
    @Test
    void no_module_is_open() {
        assertThat(modules.stream().filter(module -> module.isOpen()).map(module -> module.getIdentifier()
                        .toString()))
                .isEmpty();
    }

    private static List<Class<?>> publishedEventsOf(String module) {
        return modules.getModuleByName(module).orElseThrow().getPublishedEvents().stream()
                .<Class<?>>map(event -> event.getType().reflect())
                .toList();
    }

    @Test
    void generates_module_documentation() {
        // PlantUML component diagrams + per-module canvases under target/spring-modulith-docs/
        new Documenter(modules).writeDocumentation();
    }
}
