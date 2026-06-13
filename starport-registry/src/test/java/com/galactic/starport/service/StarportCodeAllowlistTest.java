package com.galactic.starport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.galactic.starport.repository.StarportPersistenceFacade;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class StarportCodeAllowlistTest {

    @Test
    void known_code_passes_through_unchanged() {
        StarportCodeAllowlist allowlist = loadedWith(Set.of("CORUS", "TATOO"));

        assertThat(allowlist.sanitize("CORUS")).isEqualTo("CORUS");
    }

    @Test
    void unknown_code_collapses_to_other_bucket() {
        StarportCodeAllowlist allowlist = loadedWith(Set.of("CORUS"));

        assertThat(allowlist.sanitize("HACKER-9000")).isEqualTo("other");
    }

    @Test
    void fails_closed_before_codes_are_loaded() {
        // loadKnownCodes() not invoked yet (no ApplicationReadyEvent) → empty set → everything bucketed.
        StarportCodeAllowlist allowlist = new StarportCodeAllowlist(mock(StarportPersistenceFacade.class));

        assertThat(allowlist.sanitize("CORUS")).isEqualTo("other");
    }

    private static StarportCodeAllowlist loadedWith(Set<String> codes) {
        StarportPersistenceFacade facade = mock(StarportPersistenceFacade.class);
        given(facade.findAllStarportCodes()).willReturn(codes);
        StarportCodeAllowlist allowlist = new StarportCodeAllowlist(facade);
        allowlist.loadKnownCodes();
        return allowlist;
    }
}
