package com.galactic.traderoute.contract;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
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
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class RoutesBase {

    @Autowired
    WebApplicationContext context;

    @BeforeEach
    public void setup() {
        RestAssuredMockMvc.webAppContextSetup(context);
    }
}
