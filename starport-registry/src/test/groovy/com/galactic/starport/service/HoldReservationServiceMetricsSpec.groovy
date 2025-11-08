package com.galactic.starport.service

import com.galactic.starport.repository.CustomerEntity
import com.galactic.starport.repository.CustomerRepository
import com.galactic.starport.repository.DockingBayEntity
import com.galactic.starport.repository.DockingBayRepository
import com.galactic.starport.repository.ReservationEntity
import com.galactic.starport.repository.ReservationRepository
import com.galactic.starport.repository.ShipEntity
import com.galactic.starport.repository.ShipRepository
import com.galactic.starport.repository.StarportEntity
import com.galactic.starport.repository.StarportRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import spock.lang.Specification

import java.time.Instant

class HoldReservationServiceMetricsSpec extends Specification {

    private CustomerRepository customerRepository = Mock()
    private ShipRepository shipRepository = Mock()
    private StarportRepository starportRepository = Mock()
    private DockingBayRepository dockingBayRepository = Mock()
    private ReservationRepository reservationRepository = Mock()
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry()
    private ObservationRegistry observationRegistry
    private HoldReservationService holdReservationService

    def setup() {
        // Create an ObservationRegistry and register a handler that writes metrics
        observationRegistry = ObservationRegistry.create()
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry))
        // Instantiate the service with the observation registry instead of a MeterRegistry
        holdReservationService = new HoldReservationService(
                customerRepository,
                shipRepository,
                starportRepository,
                dockingBayRepository,
                reservationRepository,
                observationRegistry)
        holdReservationService.initMetrics()
    }

    def "records successful hold allocation"() {
        given:
        def command = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .build()

        def starport = Mock(StarportEntity) {
            getCode() >> "DEST"
            toModel() >> Starport.builder().build()
        }
        def customer = Mock(CustomerEntity) {
            toModel() >> Customer.builder().build()
            getCustomerCode() >> "CUST"
        }
        def ship = Mock(ShipEntity) {
            toModel() >> Ship.builder().shipClass(Ship.ShipClass.SCOUT).build()
            getShipCode() >> "SHIP"
        }
        def bay = Mock(DockingBayEntity) {
            toModel() >> DockingBay.builder().build()
            getId() >> 5L
        }
        def saved = Mock(ReservationEntity) {
            getId() >> 1L
            getStartAt() >> command.startAt()
            getEndAt() >> command.endAt()
            getStatus() >> ReservationEntity.ReservationStatus.HOLD
        }

        customerRepository.findByCustomerCode(command.customerCode()) >> Optional.of(customer)
        shipRepository.findByShipCode(command.shipCode()) >> Optional.of(ship)
        dockingBayRepository.findFreeBay(
                command.destinationStarportCode(),
                command.shipClass().name(),
                command.startAt(),
                command.endAt()) >> Optional.of(bay)
        reservationRepository.save(_ as ReservationEntity) >> saved

        when:
        holdReservationService.allocateHold(command, starport)

        then:
        meterRegistry.get("reservations.hold.allocate").tag("status", "success").timer().count() == 1
        meterRegistry.get("reservations.hold.allocate").tag("status", "error").timer().count() == 0
    }

    def "records errors when hold allocation fails"() {
        given:
        def command = ReserveBayCommand.builder()
                .destinationStarportCode("DEST")
                .customerCode("CUST")
                .shipCode("SHIP")
                .shipClass(ReserveBayCommand.ShipClass.SCOUT)
                .startAt(Instant.now())
                .endAt(Instant.now().plusSeconds(3600))
                .build()

        customerRepository.findByCustomerCode(command.customerCode()) >> Optional.empty()

        when:
        holdReservationService.allocateHold(command, Mock(StarportEntity))

        then:
        thrown(CustomerNotFoundException)
        meterRegistry.get("reservations.hold.allocate").tag("status", "error").timer().count() == 1
        meterRegistry.get("reservations.hold.allocate").tag("status", "success").timer().count() == 0
    }
}