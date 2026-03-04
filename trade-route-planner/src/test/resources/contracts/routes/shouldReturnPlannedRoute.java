import java.util.Map;
import java.util.function.Supplier;
import org.springframework.cloud.contract.spec.Contract;

/**
 * Consumer-driven contract: POST /routes/plan with a valid payload
 * must return 200 OK with a planned-route body.
 *
 * Producer side: the real service generates random riskScore / etaHours,
 * so the response body uses dynamic matchers (regex) on the producer side
 * and concrete stub values on the consumer side.
 */
class ShouldReturnPlannedRoute implements Supplier<Contract> {

    @Override
    public Contract get() {
        return Contract.make(c -> {
            c.description("should return 200 OK with planned-route body for a valid request");

            c.request(r -> {
                r.method(r.POST());
                r.url("/routes/plan");
                r.headers(h -> h.contentType(h.applicationJson()));
                r.body(Map.of(
                        "originPortId", "SP-77-NARSHADDA",
                        "destinationPortId", "SP-02-TATOOINE",
                        "shipProfile", Map.of(
                                "class", "FREIGHTER_MK2",
                                "fuelRangeLY", 24.0)));
            });

            c.response(r -> {
                r.status(r.OK());
                r.headers(h -> h.contentType(h.applicationJson()));
                r.body(Map.of(
                        // routeId is "ROUTE-" + 8 uppercase hex chars
                        "routeId", r.$(
                                r.producer(r.regex("ROUTE-[A-F0-9]{8}")),
                                r.consumer("ROUTE-ABCD1234")),
                        // etaHours for FREIGHTER_MK2 is between 18 and 28
                        "etaHours", r.$(
                                r.producer(r.regex("(1[89]|2[0-7])\\.[0-9]+")),
                                r.consumer(22.5)),
                        // riskScore is always in [0, 1)
                        "riskScore", r.$(
                                r.producer(r.regex("0\\.[0-9]+")),
                                r.consumer(0.5)),
                        // correlationId is a UUID v4
                        "correlationId", r.$(
                                r.producer(r.regex(
                                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
                                r.consumer("12345678-1234-1234-1234-123456789012"))));
            });
        });
    }
}
