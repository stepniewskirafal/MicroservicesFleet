package com.galactic.starport.service;

import static org.mockito.Mockito.mock;

import com.galactic.starport.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Testy metryk serwisu rezerwacji.
 *
 * <p>Uwaga: treść testów jest celowo zakomentowana tak jak w oryginalnej specyfikacji Groovy.
 * Pozostawiono konfigurację setup jako wzorzec.
 */
@Execution(ExecutionMode.CONCURRENT)
class ReservationServiceMetricsTest {

    private CreateHoldReservationService holdReservationService;
    private ConfirmReservationService confirmReservationService;
    private ReserveBayValidator validateReservationCommandService;
    private FeeCalculatorService feeCalculatorService;
    private RoutePlannerService routePlannerService;

    private SimpleMeterRegistry meterRegistry;
    private ObservationRegistry observationRegistry;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        holdReservationService = mock(CreateHoldReservationService.class);
        confirmReservationService = mock(ConfirmReservationService.class);
        validateReservationCommandService = mock(ReserveBayValidator.class);
        feeCalculatorService = mock(FeeCalculatorService.class);
        routePlannerService = mock(RoutePlannerService.class);

        meterRegistry = new SimpleMeterRegistry();
        observationRegistry = ObservationRegistry.create();
        observationRegistry
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));

        reservationService = new ReservationService(
                holdReservationService,
                confirmReservationService,
                validateReservationCommandService,
                feeCalculatorService,
                routePlannerService);
    }

    /*
     * @Test
     * void recordsSuccessAndTimingWhenReservationCompletes() { ... }
     *
     * @Test
     * void recordsErrorWhenReservationFails() { ... }
     *
     * Testy zakomentowane celowo – tak jak w oryginalnej specyfikacji Groovy.
     */
}
