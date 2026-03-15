package com.galactic.starport.service.confirmreservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.galactic.starport.repository.StarportPersistenceFacade;
import com.galactic.starport.service.Reservation;
import com.galactic.starport.service.Route;
import com.galactic.starport.service.outbox.OutboxFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class ConfirmReservationServiceTest {

    private StarportPersistenceFacade persistenceFacade;
    private OutboxFacade outboxFacade;
    private ConfirmReservationService service;

    @BeforeEach
    void setUp() {
        persistenceFacade = mock(StarportPersistenceFacade.class);
        outboxFacade = mock(OutboxFacade.class);
        service = new ConfirmReservationService(ObservationRegistry.NOOP, outboxFacade, persistenceFacade);
    }

    @Test
    void should_return_confirmed_reservation_on_success() {
        Reservation expected = Reservation.builder().id(1L).status(Reservation.ReservationStatus.CONFIRMED).build();
        when(persistenceFacade.confirmReservation(eq(1L), any(), any())).thenReturn(Optional.of(expected));
        ReservationCalculation calc = new ReservationCalculation(1L, BigDecimal.TEN, null);

        Reservation result = service.confirmReservation(calc, "STARPORT-A");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void should_publish_outbox_event_after_confirmation() {
        Reservation reservation = Reservation.builder().id(2L).build();
        when(persistenceFacade.confirmReservation(eq(2L), any(), any())).thenReturn(Optional.of(reservation));
        ReservationCalculation calc = new ReservationCalculation(2L, BigDecimal.ONE, null);

        service.confirmReservation(calc, "SP-B");

        verify(outboxFacade).publishReservationConfirmedEvent(reservation);
    }

    @Test
    void should_throw_when_reservation_not_found() {
        when(persistenceFacade.confirmReservation(eq(99L), any(), any())).thenReturn(Optional.empty());
        ReservationCalculation calc = new ReservationCalculation(99L, BigDecimal.TEN, null);

        assertThatThrownBy(() -> service.confirmReservation(calc, "SP-C"))
                .isInstanceOf(ReservationConfirmationException.class)
                .hasMessageContaining("99");
    }

    @Test
    void should_not_publish_event_when_reservation_not_found() {
        when(persistenceFacade.confirmReservation(eq(99L), any(), any())).thenReturn(Optional.empty());
        ReservationCalculation calc = new ReservationCalculation(99L, BigDecimal.TEN, null);

        try {
            service.confirmReservation(calc, "SP-C");
        } catch (ReservationConfirmationException ignored) {
        }

        verify(outboxFacade, never()).publishReservationConfirmedEvent(any());
    }

    @Test
    void should_pass_fee_and_route_to_persistence() {
        Route route = Route.builder().routeCode("ROUTE-X").build();
        BigDecimal fee = new BigDecimal("99.99");
        Reservation reservation = Reservation.builder().id(3L).build();
        when(persistenceFacade.confirmReservation(3L, fee, route)).thenReturn(Optional.of(reservation));
        ReservationCalculation calc = new ReservationCalculation(3L, fee, route);

        service.confirmReservation(calc, "SP-D");

        verify(persistenceFacade).confirmReservation(3L, fee, route);
    }

    @Test
    void should_handle_null_destination_starport_code() {
        Reservation reservation = Reservation.builder().id(4L).build();
        when(persistenceFacade.confirmReservation(eq(4L), any(), any())).thenReturn(Optional.of(reservation));
        ReservationCalculation calc = new ReservationCalculation(4L, BigDecimal.ZERO, null);

        Reservation result = service.confirmReservation(calc, null);

        assertThat(result).isNotNull();
    }
}
