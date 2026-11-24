package com.galactic.fleet.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.galactic.fleet.booking.events.ReservationConfirmed;
import com.galactic.fleet.booking.repository.StarportPersistenceFacade;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction of a reservation (ADR-0043): store, then publish {@link ReservationConfirmed}
 * carrying everything a listener needs. Persistence is mocked here; the real transaction, the bay
 * lock and the delivery after commit are covered by {@code FleetEventFlowE2ETest}.
 */
class ReservationConfirmerTest {

    private static final Instant START = Instant.parse("2031-03-01T20:00:00Z");
    private static final Instant PLANNED_AT = Instant.parse("2030-12-31T23:59:00Z");
    private static final BigDecimal FEE = new BigDecimal("360.00");

    private StarportPersistenceFacade persistenceFacade;
    private ApplicationEventPublisher eventPublisher;
    private TestObservationRegistry observationRegistry;
    private ReservationConfirmer confirmer;

    @BeforeEach
    void setUp() {
        persistenceFacade = mock(StarportPersistenceFacade.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        observationRegistry = TestObservationRegistry.create();
        confirmer = new ReservationConfirmer(
                persistenceFacade, eventPublisher, observationRegistry, Tracer.NOOP, Propagator.NOOP);
    }

    @Test
    void publishes_the_confirmed_reservation_with_its_route() {
        ReserveBayCommand command = aCommand();
        Route route = Route.builder()
                .routeCode("ROUTE-ABCD1234")
                .startStarportCode("TATOO")
                .destinationStarportCode("HOTH")
                .etaHours(21.5)
                .riskScore(0.35)
                .plannedAt(PLANNED_AT)
                .build();
        // As the real persistence returns it: the route read back from the database has no plannedAt.
        Reservation stored = aStoredReservation(Route.builder()
                .routeCode("ROUTE-ABCD1234")
                .startStarportCode("TATOO")
                .destinationStarportCode("HOTH")
                .etaHours(21.5)
                .riskScore(0.35)
                .build());
        given(persistenceFacade.createConfirmedReservation(command, FEE, route)).willReturn(stored);

        Reservation result = confirmer.confirm(command, FEE, route);

        assertThat(result).isSameAs(stored);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(ReservationConfirmed.class, event -> {
            assertThat(event.reservationId()).isEqualTo(7L);
            assertThat(event.status()).isEqualTo("CONFIRMED");
            assertThat(event.starportCode()).isEqualTo("HOTH");
            assertThat(event.dockingBayLabel()).isEqualTo("B-07");
            assertThat(event.customerCode()).isEqualTo("CUST-002");
            assertThat(event.shipCode()).isEqualTo("FALCON-002");
            assertThat(event.startAt()).isEqualTo(START);
            assertThat(event.endAt()).isEqualTo(START.plusSeconds(3 * 3600));
            assertThat(event.feeCharged()).isEqualByComparingTo(FEE);
            assertThat(event.producedAt()).isBeforeOrEqualTo(Instant.now());
            assertThat(event.route())
                    .isEqualTo(new ReservationConfirmed.Route(
                            "ROUTE-ABCD1234", "TATOO", "HOTH", "FREIGHTER", 21.5, 0.35, PLANNED_AT));
        });
    }

    @Test
    void publishes_without_a_route_when_none_was_planned() {
        ReserveBayCommand command = aCommand();
        given(persistenceFacade.createConfirmedReservation(command, FEE, null)).willReturn(aStoredReservation(null));

        confirmer.confirm(command, FEE, null);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(((ReservationConfirmed) published.getValue()).route()).isNull();
    }

    @Test
    void publishes_nothing_when_no_bay_is_free() {
        ReserveBayCommand command = aCommand();
        given(persistenceFacade.createConfirmedReservation(any(), any(), any()))
                .willThrow(new NoDockingBaysAvailableException("HOTH", "FREIGHTER", START, START.plusSeconds(3600)));

        assertThatThrownBy(() -> confirmer.confirm(command, FEE, null))
                .isInstanceOf(NoDockingBaysAvailableException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void times_the_bay_allocation_and_the_whole_transaction() {
        ReserveBayCommand command = aCommand();
        given(persistenceFacade.createConfirmedReservation(command, FEE, null)).willReturn(aStoredReservation(null));

        confirmer.confirm(command, FEE, null);

        // Dashboard contract: the names and tags of both timers.
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.bay.allocate")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("starport", "HOTH")
                .hasLowCardinalityKeyValue("shipClass", "FREIGHTER");
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("reservations.confirm")
                .that()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("starport", "HOTH");
    }

    @Test
    void the_event_carries_the_trace_context_of_the_request() {
        // The registry persists the event, not the thread's trace context — so the context has to
        // travel in the event for a re-delivery to rejoin the request's trace (ADR-0043).
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext currentTraceContext = mock(CurrentTraceContext.class);
        TraceContext context = mock(TraceContext.class);
        given(tracer.currentTraceContext()).willReturn(currentTraceContext);
        given(currentTraceContext.context()).willReturn(context);
        Propagator propagator = new Propagator() {
            @Override
            public java.util.List<String> fields() {
                return java.util.List.of("traceparent");
            }

            @Override
            public <C> void inject(TraceContext ctx, C carrier, Setter<C> setter) {
                assertThat(ctx).isSameAs(context);
                setter.set(carrier, "traceparent", traceparent);
            }

            @Override
            public <C> io.micrometer.tracing.Span.Builder extract(C carrier, Getter<C> getter) {
                throw new UnsupportedOperationException();
            }
        };
        confirmer =
                new ReservationConfirmer(persistenceFacade, eventPublisher, observationRegistry, tracer, propagator);
        ReserveBayCommand command = aCommand();
        given(persistenceFacade.createConfirmedReservation(command, FEE, null)).willReturn(aStoredReservation(null));

        confirmer.confirm(command, FEE, null);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(((ReservationConfirmed) published.getValue()).traceparent()).isEqualTo(traceparent);
    }

    @Test
    void outside_a_trace_the_event_carries_no_trace_context() {
        ReserveBayCommand command = aCommand();
        given(persistenceFacade.createConfirmedReservation(command, FEE, null)).willReturn(aStoredReservation(null));

        confirmer.confirm(command, FEE, null);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(((ReservationConfirmed) published.getValue()).traceparent()).isNull();
    }

    @Test
    void confirm_is_the_transaction_boundary() throws Exception {
        // Storing the reservation and recording the event publication must commit together.
        assertThat(ReservationConfirmer.class
                        .getMethod("confirm", ReserveBayCommand.class, BigDecimal.class, Route.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    private static ReserveBayCommand aCommand() {
        return ReserveBayCommand.builder()
                .startStarportCode("TATOO")
                .destinationStarportCode("HOTH")
                .customerCode("CUST-002")
                .shipCode("FALCON-002")
                .shipClass(ReserveBayCommand.ShipClass.FREIGHTER)
                .startAt(START)
                .endAt(START.plusSeconds(3 * 3600))
                .requestRoute(true)
                .build();
    }

    private static Reservation aStoredReservation(Route route) {
        return Reservation.builder()
                .id(7L)
                .status(Reservation.ReservationStatus.CONFIRMED)
                .starport(Starport.builder().code("HOTH").build())
                .dockingBay(DockingBay.builder().bayLabel("B-07").build())
                .customer(Customer.builder().customerCode("CUST-002").build())
                .ship(Ship.builder().shipCode("FALCON-002").build())
                .startAt(START)
                .endAt(START.plusSeconds(3 * 3600))
                .feeCharged(FEE)
                .route(route)
                .build();
    }
}
