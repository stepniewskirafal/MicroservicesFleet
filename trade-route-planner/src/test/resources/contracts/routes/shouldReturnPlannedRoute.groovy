import org.springframework.cloud.contract.spec.Contract

/**
 * Consumer-driven contract: POST /routes/plan with a valid payload
 * must return 200 OK with a planned-route body.
 *
 * Producer side: the real service generates random riskScore / etaHours,
 * so the response body uses dynamic matchers (regex / anyDouble) on both sides.
 */
Contract.make {
    description "should return 200 OK with planned-route body for a valid request"

    request {
        method POST()
        url "/routes/plan"
        headers {
            contentType(applicationJson())
        }
        body([
            originPortId     : "SP-77-NARSHADDA",
            destinationPortId: "SP-02-TATOOINE",
            shipProfile      : [
                "class"     : "FREIGHTER_MK2",
                fuelRangeLY : 24.0
            ]
        ])
    }

    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            // routeId is "ROUTE-" + 8 uppercase hex chars
            routeId      : $(consumer(regex("ROUTE-[A-F0-9]{8}")),
                            producer("ROUTE-ABCD1234")),
            // etaHours for FREIGHTER_MK2 is between 18 and 28
            etaHours     : $(consumer(regex("(1[89]|2[0-7])\\.[0-9]+")),
                            producer(22.5)),
            // riskScore is always in [0, 1)
            riskScore    : $(consumer(regex("0\\.[0-9]+")),
                            producer(0.5)),
            // correlationId is a UUID v4
            correlationId: $(consumer(regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
                            producer("12345678-1234-1234-1234-123456789012"))
        ])
    }
}
