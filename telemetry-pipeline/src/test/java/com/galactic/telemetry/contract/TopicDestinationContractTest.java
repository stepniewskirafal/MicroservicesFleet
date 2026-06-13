package com.galactic.telemetry.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.yaml.snakeyaml.Yaml;

/**
 * Cross-service contract test: for every Kafka channel, the PRODUCING binding's destination must
 * equal the CONSUMING binding's destination, and both must equal the canonical {@link FleetTopics}
 * name. This catches the classic silent failure where one service's {@code application.yml}
 * destination is edited and the other side drifts — messages then vanish with no error and no
 * compile failure. The test reads each service's real {@code application.yml} from the reactor.
 */
@Execution(ExecutionMode.CONCURRENT)
class TopicDestinationContractTest {

    @Test
    void reservation_channel_producer_and_consumer_share_canonical_destination() {
        String producer = destination("starport-registry", "reservationConfirmed-out-0");
        String consumer = destination("telemetry-pipeline", "reservationPipeline-in-0");

        assertThat(producer)
                .as("starport producer destination")
                .isEqualTo(FleetTopics.STARPORT_RESERVATIONS);
        assertThat(consumer)
                .as("telemetry consumer destination")
                .isEqualTo(FleetTopics.STARPORT_RESERVATIONS);
        assertThat(producer)
                .as("producer and consumer destinations must not drift")
                .isEqualTo(consumer);
    }

    @Test
    void route_channel_producer_and_consumer_share_canonical_destination() {
        String producer = destination("trade-route-planner", "routePlanned-out-0");
        String consumer = destination("telemetry-pipeline", "routePipeline-in-0");

        assertThat(producer).as("planner producer destination").isEqualTo(FleetTopics.STARPORT_ROUTE_PLANNED);
        assertThat(consumer).as("telemetry consumer destination").isEqualTo(FleetTopics.STARPORT_ROUTE_PLANNED);
        assertThat(producer)
                .as("producer and consumer destinations must not drift")
                .isEqualTo(consumer);
    }

    @SuppressWarnings("unchecked")
    private static String destination(String module, String binding) {
        Path yamlPath = repoRoot().resolve(module).resolve("src/main/resources/application.yml");
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yamlPath)) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + yamlPath, e);
        }
        Map<String, Object> bindings = nested(root, "spring", "cloud", "stream", "bindings");
        Object bindingCfg = bindings.get(binding);
        if (!(bindingCfg instanceof Map)) {
            throw new AssertionError("Binding '" + binding + "' not found in " + module + "/application.yml");
        }
        Object destination = ((Map<String, Object>) bindingCfg).get("destination");
        if (!(destination instanceof String s)) {
            throw new AssertionError("Binding '" + binding + "' has no destination in " + module);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> map, String... keys) {
        Map<String, Object> current = map;
        for (String key : keys) {
            Object next = current.get(key);
            if (!(next instanceof Map)) {
                throw new AssertionError("Missing YAML path key: " + key);
            }
            current = (Map<String, Object>) next;
        }
        return current;
    }

    /** Walk up from the test working directory until the directory holding the service modules is found. */
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("starport-registry"))
                    && Files.isDirectory(dir.resolve("telemetry-pipeline"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Cannot locate repo root from " + System.getProperty("user.dir"));
    }
}
