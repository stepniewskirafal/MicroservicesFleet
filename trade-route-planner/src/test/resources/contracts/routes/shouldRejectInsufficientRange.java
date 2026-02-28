import java.util.Map;
import java.util.function.Supplier;
import org.springframework.cloud.contract.spec.Contract;

/**
 * Consumer-driven contract: POST /routes/plan with fuelRangeLY below the 1 LY minimum
 * must return 422 Unprocessable Entity with a ROUTE_REJECTED body.
 *
 * Note: fuelRangeLY=0.5 passes @Positive bean validation (> 0) but is rejected
 * by the service business rule (minimum 1.0 LY).
 */
class ShouldRejectInsufficientRange implements Supplier<Contract> {

    @Override
    public Contract get() {
        return Contract.make(c -> {
            c.description("should return 422 ROUTE_REJECTED when fuelRangeLY is below the 1 LY minimum");

            c.request(r -> {
                r.method(r.POST());
                r.url("/routes/plan");
                r.headers(h -> h.contentType(h.applicationJson()));
                r.body(Map.of(
                        "originPortId", "SP-77-NARSHADDA",
                        "destinationPortId", "SP-02-TATOOINE",
                        "shipProfile", Map.of(
                                "class", "SCOUT",
                                "fuelRangeLY", 0.5)));
            });

            c.response(r -> {
                r.status(422);
                r.headers(h -> h.contentType(h.applicationJson()));
                r.body(Map.of(
                        "error", "ROUTE_REJECTED",
                        "reason", "INSUFFICIENT_RANGE",
                        "details", r.$(
                                r.producer(r.regex("[\\S\\s]+")),
                                r.consumer("Required minimum fuel range is 1.0 LY, but ship only has 0.5 LY"))));
            });
        });
    }
}
