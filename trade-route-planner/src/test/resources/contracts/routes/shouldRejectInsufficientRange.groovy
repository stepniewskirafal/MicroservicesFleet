import org.springframework.cloud.contract.spec.Contract

/**
 * Consumer-driven contract: POST /routes/plan with fuelRangeLY below the 1 LY minimum
 * must return 422 Unprocessable Entity with a ROUTE_REJECTED body.
 *
 * Note: fuelRangeLY=0.5 passes @Positive bean validation (> 0) but is rejected
 * by the service business rule (minimum 1.0 LY).
 */
Contract.make {
    description "should return 422 ROUTE_REJECTED when fuelRangeLY is below the 1 LY minimum"

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
                "class"     : "SCOUT",
                fuelRangeLY : 0.5
            ]
        ])
    }

    response {
        status UNPROCESSABLE_ENTITY()
        headers {
            contentType(applicationJson())
        }
        body([
            error  : "ROUTE_REJECTED",
            reason : "INSUFFICIENT_RANGE",
            details: $(
                consumer(anyNonEmptyString()),
                producer("Required minimum fuel range is 1.0 LY, but ship only has 0.5 LY")
            )
        ])
    }
}
