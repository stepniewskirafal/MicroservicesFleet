package com.galactic.starport.service;

public class RestServices {

    /*
     */
    /**
     * Potwierdza rezerwację HOLD. Jeżeli przekazano obiekt trasy, zapisuje go
     * w rezerwacji, oblicza opłatę i ustawia status CONFIRMED. Jeśli trasa
     * jest null (np. nie żądano planowania), ustawia tylko status i opłatę.
     */
    /*
    @Transactional
    public Reservation confirmReservation(Reservation hold, Route route, BigDecimal fee) {
        // aktualizujemy stan rezerwacji w zależności od obecności trasy
        if (route != null) {
            hold.confirmReservation(route, fee);
        } else {
            hold.setStatus(Reservation.ReservationStatus.CONFIRMED);
            hold.setFeeAmount(fee);
        }
        final Reservation saved;
        try {
            saved = starportGateway.save(hold);
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while saving reservation", dae);
        }

        // budujemy i zapisujemy payload zdarzenia w outboxie
        var payload = ReservationCreated.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .occurredAt(java.time.Instant.now())
                .starportCode(saved.getDockingBay().getStarport().getCode())
                .reservationId(saved.getId().toString())
                .bayNumber(saved.getDockingBay().getId().toString())
                .shipId(saved.getShipId())
                .shipClass(saved.getShipClass().name())
                .startAt(saved.getPeriod().getStartAt())
                .endAt(saved.getPeriod().getEndAt())
                .feeCharged(String.valueOf(saved.getFeeAmount()))
                .build();

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
        outboxPort.save(
                "ReservationCreated",
                "reservationCreated-out-0",
                saved.getDockingBay().getStarport().getId().toString(),
                json,
                Map.of(
                        "partitionKey",
                        saved.getDockingBay().getStarport().getId().toString(),
                        "contentType",
                        "application/json"));
        return saved;
    }

    */
    /**
     * Zwalnia rezerwację w stanie HOLD, zmieniając status na CANCELLED.
     */
    /*
    @Transactional
    public void releaseHold(Reservation reservation) {
        reservation.setStatus(Reservation.ReservationStatus.CANCELLED);
        try {
            starportGateway.save(reservation);
        } catch (DataAccessException dae) {
            throw new RepositoryUnavailableException("Database error while releasing hold", dae);
        }
    }*/

}
