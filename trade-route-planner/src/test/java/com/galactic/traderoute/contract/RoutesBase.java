package com.galactic.traderoute.contract;

import com.galactic.traderoute.TradeRoutePlannerApplication;
import com.galactic.traderoute.port.out.RouteEventPublisher;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for Spring Cloud Contract server-side (producer) tests.
 *
 * <p>The {@code spring-cloud-contract-maven-plugin} generates a test class in
 * {@code target/generated-test-sources/contracts/} whose package matches this class. The generated
 * class extends {@code RoutesBase} and inherits the full Spring context + MockMvc setup.
 *
 * <p>Run producer contract verification with:
 *
 * <pre>
 *   mvn test                              # runs all tests including generated contract tests
 *   mvn spring-cloud-contract:generateTests test   # explicit generate + test
 * </pre>
 */
// Explicit config classes: the generated RoutesTest lands in the plugin's default package
// (org.springframework.cloud.contract.verifier.tests), so the default upward search for a
// @SpringBootConfiguration would fail. Declaring the app class here makes the context load
// regardless of the generated test's package.
@SpringBootTest(classes = TradeRoutePlannerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class RoutesBase {

    @Autowired
    WebApplicationContext context;

    // Stub the outbound Kafka port: contract tests verify the HTTP contract, not event delivery,
    // and the real StreamBridge would block ~60s on broker metadata without a broker.
    @MockitoBean
    RouteEventPublisher routeEventPublisher;

    @BeforeEach
    public void setup() {
        RestAssuredMockMvc.webAppContextSetup(context);
    }
}
