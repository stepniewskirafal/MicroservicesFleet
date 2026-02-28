package com.galactic.traderoute.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import com.galactic.traderoute.application.PlanRouteService;
import com.galactic.traderoute.domain.model.PlannedRoute;
import com.galactic.traderoute.domain.model.RouteRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests that verify {@link PlanRouteService} is safe under concurrent access.
 *
 * <p>Two parallel-execution strategies are exercised:
 * <ol>
 *   <li><b>JUnit 5 native parallelism</b>: {@link RepeatedTest} with
 *       {@link ExecutionMode#CONCURRENT} — JUnit runs the test repetitions concurrently on its
 *       own thread pool (configured via {@code junit-platform.properties}).
 *   <li><b>Internal concurrency</b>: a single test creates many threads and uses an
 *       {@link ExecutorService} to fire requests simultaneously, verifying no corruption occurs.
 * </ol>
 */
@Execution(ExecutionMode.CONCURRENT)
class ParallelRoutePlannerTest {

    private static final PlanRouteService SERVICE =
            new PlanRouteService(new SimpleMeterRegistry(), ObservationRegistry.NOOP);

    @RepeatedTest(value = 20, name = "concurrent planning #{currentRepetition}/{totalRepetitions}")
    void should_produce_valid_route_under_concurrent_junit5_execution(TestInfo info) {
        int repetition = Integer.parseInt(
                info.getDisplayName().replaceAll(".*#(\\d+)/.*", "$1"));

        RouteRequest request = RouteRequest.builder()
                .originPortId("SP-PARALLEL-" + repetition)
                .destinationPortId("SP-DEST-" + repetition)
                .shipClass("CRUISER")
                .fuelRangeLY(15.0)
                .build();

        PlannedRoute route = SERVICE.planRoute(request);

        assertThat(route.routeId()).matches("ROUTE-[A-F0-9]{8}");
        assertThat(route.riskScore()).isBetween(0.0, 1.0);
        assertThat(route.etaHours()).isBetween(12.0, 22.0);
    }

    @Test
    void should_handle_forty_concurrent_requests_without_data_corruption() throws Exception {
        int threadCount = 40;
        CopyOnWriteArrayList<PlannedRoute> results = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Void>> tasks = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            tasks.add(() -> {
                try {
                    RouteRequest req = RouteRequest.builder()
                            .originPortId("SP-THREAD-" + idx)
                            .destinationPortId("SP-DEST-" + idx)
                            .shipClass(shipClassFor(idx))
                            .fuelRangeLY(10.0 + idx)
                            .build();
                    PlannedRoute route = SERVICE.planRoute(req);
                    results.add(route);
                    callCount.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        // Await all futures (re-throws if any threw an unexpected exception)
        for (Future<Void> f : futures) {
            f.get();
        }

        assertThat(errors).as("No thread should have thrown an unexpected exception").isEmpty();
        assertThat(callCount.get()).isEqualTo(threadCount);

        results.forEach(r -> {
            assertThat(r.routeId()).as("routeId must match ROUTE- format").matches("ROUTE-[A-F0-9]{8}");
            assertThat(r.riskScore()).as("riskScore must be in [0,1]").isBetween(0.0, 1.0);
            assertThat(r.etaHours()).as("etaHours must be positive").isPositive();
        });
    }

    @RepeatedTest(value = 10, name = "mixed ship class stress #{currentRepetition}")
    void should_compute_correct_eta_bounds_under_parallel_stress() {
        String[] shipClasses = {"SCOUT", "FREIGHTER", "FREIGHTER_MK2", "CRUISER", "DESTROYER"};
        double[] minEtas = {8.0, 18.0, 18.0, 12.0, 20.0};
        double[] maxEtas = {18.0, 28.0, 28.0, 22.0, 30.0};

        for (int i = 0; i < shipClasses.length; i++) {
            RouteRequest req = RouteRequest.builder()
                    .originPortId("SP-A")
                    .destinationPortId("SP-B")
                    .shipClass(shipClasses[i])
                    .fuelRangeLY(20.0)
                    .build();

            PlannedRoute route = SERVICE.planRoute(req);

            assertThat(route.etaHours())
                    .as("ETA for %s", shipClasses[i])
                    .isBetween(minEtas[i], maxEtas[i]);
        }
    }

    @Test
    void counter_should_be_incremented_atomically_by_all_threads() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlanRouteService localService = new PlanRouteService(registry, ObservationRegistry.NOOP);

        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                localService.planRoute(RouteRequest.builder()
                        .originPortId("SP-A")
                        .destinationPortId("SP-B")
                        .shipClass("SCOUT")
                        .fuelRangeLY(5.0)
                        .build());
                return null;
            });
        }

        executor.invokeAll(tasks).forEach(f -> {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.shutdown();

        double actualCount = registry.get("routes.planned.count").counter().count();
        assertThat(actualCount)
                .as("All %d counter increments must be visible", threadCount)
                .isEqualTo(threadCount);
    }

    private static String shipClassFor(int idx) {
        String[] classes = {"SCOUT", "FREIGHTER", "FREIGHTER_MK2", "CRUISER", "DESTROYER"};
        return classes[idx % classes.length];
    }
}
