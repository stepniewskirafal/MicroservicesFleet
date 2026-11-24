package com.galactic.starport.service.routeplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Every {@code outcome} series must exist at zero as soon as the client is constructed, before any
 * call. PromQL's {@code increase()} does not assume a series started at zero, so a counter created
 * lazily on its first event is first scraped at 1 and the "must stay at zero" alert misses that
 * event. The expected values are spelled out here on purpose — iterating the client's own list
 * would not notice an outcome that was added to the class but left out of the registration.
 */
class TradeRoutePlannerClientOutcomeRegistrationTest {

    private static final List<String> EXPECTED_OUTCOMES = List.of(
            "success", "domain", "infrastructure", "empty_response", "circuit_open", "misrouted", "no_instances");

    @Test
    void everyOutcomeSeriesIsRegisteredAtZeroBeforeTheFirstCall() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new TradeRoutePlannerClient(
                RestClient.builder().baseUrl("http://trade-route-planner").build(), meterRegistry, new ObjectMapper());

        for (String outcome : EXPECTED_OUTCOMES) {
            Counter counter = meterRegistry
                    .find(TradeRoutePlannerClient.METRIC_OUTCOME)
                    .tag("outcome", outcome)
                    .counter();
            assertNotNull(counter, "outcome series missing at startup: " + outcome);
            assertEquals(0.0, counter.count(), "outcome series must start at zero: " + outcome);
        }
        assertEquals(
                EXPECTED_OUTCOMES.size(),
                meterRegistry
                        .find(TradeRoutePlannerClient.METRIC_OUTCOME)
                        .counters()
                        .size(),
                "an outcome was added to the client without being listed in this test");
    }
}
