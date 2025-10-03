package com.galactic.starport.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TimeRange {

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    public void validate() {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("Invalid time window: start_at must be < end_at");
        }
    }

    public boolean overlaps(TimeRange other) {
        // [start, end) konwencja: overlap jeśli NEW.start < OLD.end && OLD.start < NEW.end
        return other != null && this.startAt.isBefore(other.endAt) && other.startAt.isBefore(this.endAt);
    }
}
