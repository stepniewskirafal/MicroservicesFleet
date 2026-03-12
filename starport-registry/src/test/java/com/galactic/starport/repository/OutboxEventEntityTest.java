package com.galactic.starport.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class OutboxEventEntityTest {

    @Test
    void should_start_with_pending_status() {
        OutboxEventEntity entity = new OutboxEventEntity();

        assertThat(entity.getStatus()).isEqualTo(OutboxEventEntity.OutboxStatus.PENDING);
    }

    @Test
    void should_start_with_zero_attempts() {
        OutboxEventEntity entity = new OutboxEventEntity();

        assertThat(entity.getAttempts()).isZero();
    }

    @Test
    void markSent_should_set_status_to_sent() {
        OutboxEventEntity entity = new OutboxEventEntity();

        entity.markSent();

        assertThat(entity.getStatus()).isEqualTo(OutboxEventEntity.OutboxStatus.SENT);
    }

    @Test
    void markSent_should_set_sentAt_timestamp() {
        OutboxEventEntity entity = new OutboxEventEntity();

        entity.markSent();

        assertThat(entity.getSentAt()).isNotNull();
    }

    @Test
    void bumpAttempts_should_increment_by_one() {
        OutboxEventEntity entity = new OutboxEventEntity();

        entity.bumpAttempts();
        entity.bumpAttempts();
        entity.bumpAttempts();

        assertThat(entity.getAttempts()).isEqualTo(3);
    }

    @Test
    void markFailed_should_set_status_to_failed() {
        OutboxEventEntity entity = new OutboxEventEntity();

        entity.markFailed();

        assertThat(entity.getStatus()).isEqualTo(OutboxEventEntity.OutboxStatus.FAILED);
    }

    @Test
    void markFailed_after_bumps_should_retain_attempt_count() {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.bumpAttempts();
        entity.bumpAttempts();

        entity.markFailed();

        assertThat(entity.getAttempts()).isEqualTo(2);
        assertThat(entity.getStatus()).isEqualTo(OutboxEventEntity.OutboxStatus.FAILED);
    }
}
