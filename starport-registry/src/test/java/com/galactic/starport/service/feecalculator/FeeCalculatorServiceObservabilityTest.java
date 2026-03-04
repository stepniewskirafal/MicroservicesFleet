package com.galactic.starport.service.feecalculator;

import com.galactic.starport.service.ReserveBayCommand;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.tck.MeterRegistryAssert;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class FeeCalculatorServiceObservabilityTest {

    private static final String DEST = "DEF";

    private MeterRegistry meterRegistry;
    private TestObservationRegistry observationRegistry;
    private FeeCalculatorService feeCalculatorService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        observationRegistry = TestObservationRegistry.create();
        feeCalculatorService = new FeeCalculatorService(meterRegistry, observationRegistry);
    }

    @Test
    void calculateFeeEmitsObservationWithExpectedLowCardinalityTags() {
        // given
        Instant start = Instant.parse("2003-01-01T00:00:00Z");
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(start)
                .endAt(start.plusSeconds(3600L))
                .requestRoute(true)
                .build();

        // when
        BigDecimal fee = feeCalculatorService.calculateFee(cmd);

        // then - powstała obserwacja z odpowiednimi tagami
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.fees.calculate")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("starport", DEST)
                .hasLowCardinalityKeyValue("shipClass", "SCOUT");

        // and - zwrócona opłata jest dodatnia
        assert fee.compareTo(BigDecimal.ZERO) > 0 : "Fee should be positive";
    }

    @Test
    void calculateFeeRecordsFeeAndHoursDistributionSummaries() {
        // given
        Instant start = Instant.parse("2003-01-01T00:00:00Z");
        long hours = 2L;
        ReserveBayCommand cmd = ReserveBayCommand.builder()
                .destinationStarportCode(DEST)
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.FREIGHTER)
                .startAt(start)
                .endAt(start.plusSeconds(hours * 3600L))
                .requestRoute(true)
                .build();

        // when
        BigDecimal fee = feeCalculatorService.calculateFee(cmd);

        // then - metryki zostały zarejestrowane w MeterRegistry
        MeterRegistryAssert.assertThat(meterRegistry)
                .hasMeterWithName("reservations.fees.calculated.amount")
                .hasMeterWithName("reservations.fees.calculated.hours");

        // and - podsumowanie kwoty opłaty jest spójne z wynikiem biznesowym
        // "starport" tag removed from the metric — dynamic, high-cardinality values break
        // Prometheus label-consistency; the dashboard aggregates by shipClass anyway.
        DistributionSummary feeSummary = meterRegistry.get("reservations.fees.calculated.amount")
                .tag("shipClass", "FREIGHTER")
                .summary();
        assert feeSummary.count() == 1 : "Fee summary count should be 1";
        assert feeSummary.totalAmount() == fee.doubleValue() : "Fee summary total should match fee";
        assert "cr".equals(feeSummary.getId().getBaseUnit()) : "Base unit should be 'cr'";
        assert "Calculated reservation fee amount in Credits".equals(feeSummary.getId().getDescription())
                : "Description should match";

        // and - podsumowanie liczby godzin jest spójne z czasem naliczania
        DistributionSummary hoursSummary = meterRegistry.get("reservations.fees.calculated.hours")
                .tag("shipClass", "FREIGHTER")
                .summary();
        assert hoursSummary.count() == 1 : "Hours summary count should be 1";
        assert hoursSummary.totalAmount() == (double) hours : "Hours summary total should match hours";
        assert "hours".equals(hoursSummary.getId().getBaseUnit()) : "Base unit should be 'hours'";
        assert "Charged hours used to calculate reservation fee".equals(hoursSummary.getId().getDescription())
                : "Description should match";
    }
}
