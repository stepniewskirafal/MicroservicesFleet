package com.galactic.starport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.galactic.starport.service.confirmreservation.ConfirmReservationFacade;
import com.galactic.starport.service.holdreservation.HoldReservationFacade;
import com.galactic.starport.service.reservationcalculation.ReservationCalculation;
import com.galactic.starport.service.reservationcalculation.ReservationCalculationFacade;
import com.galactic.starport.service.validation.ReserveBayValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class ReservationServiceTest {

    @Mock
    private HoldReservationFacade holdReservationFacade;

    @Mock
    private ConfirmReservationFacade confirmReservationFacade;

    @Mock
    private ReserveBayValidator reservationValidator;

    @Mock
    private ReservationCalculationFacade reservationCalculationFacade;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                holdReservationFacade,
                confirmReservationFacade,
                reservationValidator,
                reservationCalculationFacade,
                new SimpleMeterRegistry());
    }

    @Test
    void should_return_confirmed_reservation_when_all_steps_succeed() {
        // given
        ReserveBayCommand cmd = aCommand();
        Long reservationId = 42L;
        ReservationCalculation calc = new ReservationCalculation(reservationId, BigDecimal.valueOf(100), null);
        Reservation expected = Reservation.builder()
                .id(reservationId)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .build();

        given(holdReservationFacade.createHoldReservation(cmd)).willReturn(reservationId);
        given(reservationCalculationFacade.calculate(reservationId, cmd)).willReturn(calc);
        given(confirmReservationFacade.confirmReservation(calc, "DEF")).willReturn(expected);

        // when
        Optional<Reservation> result = reservationService.reserveBay(cmd);

        // then
        assertThat(result).isPresent().contains(expected);
        then(reservationValidator).should().validate(cmd);
    }

    @Test
    void should_validate_command_before_creating_hold() {
        // given
        ReserveBayCommand cmd = aCommand();
        given(holdReservationFacade.createHoldReservation(cmd)).willReturn(1L);
        given(reservationCalculationFacade.calculate(any(), any()))
                .willReturn(new ReservationCalculation(1L, BigDecimal.TEN, null));
        given(confirmReservationFacade.confirmReservation(any(), any()))
                .willReturn(Reservation.builder().id(1L).build());

        // when
        reservationService.reserveBay(cmd);

        // then
        then(reservationValidator).should().validate(cmd);
    }

    @Test
    void should_not_create_hold_when_validation_fails() {
        // given
        ReserveBayCommand cmd = aCommand();
        var validationException = new InvalidReservationTimeException(cmd.startAt(), cmd.endAt());
        willThrow(validationException).given(reservationValidator).validate(cmd);

        // when / then
        assertThatThrownBy(() -> reservationService.reserveBay(cmd))
                .isInstanceOf(InvalidReservationTimeException.class);

        then(holdReservationFacade).should(never()).createHoldReservation(any());
    }

    @Test
    void should_propagate_starport_not_found_exception() {
        // given
        ReserveBayCommand cmd = aCommand();
        willThrow(new StarportNotFoundException("DEF")).given(reservationValidator).validate(cmd);

        // when / then
        assertThatThrownBy(() -> reservationService.reserveBay(cmd))
                .isInstanceOf(StarportNotFoundException.class);
    }

    @Test
    void should_pass_correct_destination_code_to_confirm_reservation() {
        // given
        ReserveBayCommand cmd = aCommand();
        Long reservationId = 10L;
        ReservationCalculation calc = new ReservationCalculation(reservationId, BigDecimal.TEN, null);

        given(holdReservationFacade.createHoldReservation(cmd)).willReturn(reservationId);
        given(reservationCalculationFacade.calculate(reservationId, cmd)).willReturn(calc);
        given(confirmReservationFacade.confirmReservation(calc, "DEF"))
                .willReturn(Reservation.builder().id(reservationId).build());

        // when
        reservationService.reserveBay(cmd);

        // then
        then(confirmReservationFacade).should().confirmReservation(eq(calc), eq("DEF"));
    }

    private static ReserveBayCommand aCommand() {
        return ReserveBayCommand.builder()
                .destinationStarportCode("DEF")
                .startStarportCode("ALPHA-BASE")
                .customerCode("CUST-001")
                .shipCode("SS-Enterprise-01")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.parse("2004-01-01T00:00:00Z"))
                .endAt(Instant.parse("2004-01-01T01:00:00Z"))
                .requestRoute(true)
                .build();
    }
}
