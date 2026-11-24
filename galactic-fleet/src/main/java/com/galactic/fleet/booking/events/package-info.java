/**
 * Named interface {@code events} of the {@code booking} module — its only exposed types.
 * Other modules react to these events after the commit; nobody calls into the module
 * (ADR-0043).
 */
@NamedInterface("events")
package com.galactic.fleet.booking.events;

import org.springframework.modulith.NamedInterface;
