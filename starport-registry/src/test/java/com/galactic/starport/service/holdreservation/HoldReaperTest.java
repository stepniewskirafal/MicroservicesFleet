package com.galactic.starport.service.holdreservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.galactic.starport.repository.StarportPersistenceFacade;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class HoldReaperTest {

    private static final long TTL_MS = 120_000L;

    @Mock
    private StarportPersistenceFacade persistenceFacade;

    @Test
    void increments_metric_by_number_of_reaped_holds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        given(persistenceFacade.reapStaleHolds(any())).willReturn(3);
        HoldReaper reaper = new HoldReaper(persistenceFacade, registry, TTL_MS);

        reaper.reapStaleHolds();

        assertThat(registry.get("reservations.hold.reaped").counter().count()).isEqualTo(3.0);
    }

    @Test
    void counter_stays_zero_when_nothing_to_reap() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        given(persistenceFacade.reapStaleHolds(any())).willReturn(0);
        HoldReaper reaper = new HoldReaper(persistenceFacade, registry, TTL_MS);

        reaper.reapStaleHolds();

        assertThat(registry.get("reservations.hold.reaped").counter().count()).isZero();
    }

    @Test
    void passes_cutoff_of_now_minus_ttl() {
        given(persistenceFacade.reapStaleHolds(any())).willReturn(0);
        HoldReaper reaper = new HoldReaper(persistenceFacade, new SimpleMeterRegistry(), TTL_MS);
        Instant expectedLowerBound = Instant.now().minusMillis(TTL_MS).minusSeconds(5);

        reaper.reapStaleHolds();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(persistenceFacade).reapStaleHolds(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(expectedLowerBound, Instant.now());
    }
}
