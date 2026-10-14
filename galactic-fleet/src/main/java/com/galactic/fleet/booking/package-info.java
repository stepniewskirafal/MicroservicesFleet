/**
 * Module {@code booking}: docking-bay reservations — REST, bay allocation, the berth tariff, JPA.
 * The only database owner.
 *
 * <p>Exposes one named interface, {@code events} ({@link
 * com.galactic.fleet.booking.events.ReservationConfirmed}); nobody calls into the module. It may use
 * only the {@code api} named interface of {@code routeplanning} (ADR-0044) — the one synchronous
 * call between modules, made before the reservation's transaction opens (ADR-0046).
 */
@ApplicationModule(
        displayName = "Booking",
        type = ApplicationModule.Type.CLOSED,
        allowedDependencies = "routeplanning::api")
package com.galactic.fleet.booking;

import org.springframework.modulith.ApplicationModule;
