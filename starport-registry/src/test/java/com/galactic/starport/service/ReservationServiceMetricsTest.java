package com.galactic.starport.service;

import static org.mockito.Mockito.mock;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculationFacade;
import com.galactic.starport.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;

/**
 * Reservation service metrics tests.
 *
 * <p>Note: test bodies are intentionally commented out as in the original Groovy specification.
 * The setup configuration is kept as a reference pattern.
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
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));

        reservationService = new ReservationService(
                holdReservationFacade,
                confirmReservationFacade,
                validateReservationCommandService,
                reservationCalculationFacade,
                meterRegistry,
                Tracer.NOOP,
                starport -> starport);
    }

    static class LogingEvent implements Observation.Event      {
        private final String name;
        private final Class<?> source;

        LogingEvent(String name, Class<?> source) {
            this.name = name;
            this.source = source;
        }

        public Class<?> getSource() {
            return source;
        }

        @Override
        public String getName() {
            return this.name;
        }
    }

    static class LoggingObservationHandler implements ObservationHandler<Observation.Context> {
        @Override
        public void onEvent(Observation.Event event, Observation.Context context) {
            if(event instanceof LogingEvent logEvent) {
                LoggerFactory.getLogger( logEvent.getSource() ).info(logEvent.getName());
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }




    /*
     * @Test
     * void recordsSuccessAndTimingWhenReservationCompletes() { ... }
     *
     * @Test
     * void recordsErrorWhenReservationFails() { ... }
     *
     * Tests intentionally commented out – as in the original Groovy specification.
     */
}
