package com.galactic.starport.service.outbox;

import com.galactic.starport.service.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class KafkaOutboxFacade implements OutboxFacade {
    private final OutboxAppender outboxAppender;

    @Override
    public void publishReservationConfirmedEvent(Reservation reservation) {
        outboxAppender.publishReservationConfirmedEvent(reservation);
    }
}
