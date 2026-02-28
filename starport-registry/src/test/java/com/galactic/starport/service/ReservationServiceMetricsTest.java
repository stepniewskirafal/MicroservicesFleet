package com.galactic.starport.service;

import static org.mockito.Mockito.mock;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculationFacade;
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

    private HoldReservationFacade holdReservationFacade;
    private ConfirmReservationFacade confirmReservationFacade;
    private ReserveBayValidator validateReservationCommandService;
    private ReservationCalculationFacade reservationCalculationFacade;

    private SimpleMeterRegistry meterRegistry;
    private ObservationRegistry observationRegistry;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        holdReservationFacade = mock(HoldReservationFacade.class);
        confirmReservationFacade = mock(ConfirmReservationFacade.class);
        validateReservationCommandService = mock(ReserveBayValidator.class);
        reservationCalculationFacade = mock(ReservationCalculationFacade.class);

        meterRegistry = new SimpleMeterRegistry();
        observationRegistry = ObservationRegistry.create();
        observationRegistry
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));

        reservationService = new ReservationService(
                holdReservationFacade,
                confirmReservationFacade,
                validateReservationCommandService,
                reservationCalculationFacade);
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
