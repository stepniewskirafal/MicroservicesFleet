package com.galactic.starport.repository;

final class ReservationMapper {

    private ReservationMapper() {}

    /*    static Reservation toDomain(ReservationEntity entity) {
            if (entity == null) {
                return null;
            }


    // mapowanie listy tras
            List<Route> routes = entity.getRoutes() == null
                    ? new ArrayList<>()
                    : RouteMapper.toDomainList(entity.getRoutes());


            return Reservation.builder()
                    .id(entity.getId())
                    .starport(StarportMapper.toDomain(entity.getStarportEntity()))
                    .dockingBay(DockingBayMapper.toDomain(entity.getDockingBay()))
                    .customer(CustomerMapper.toDomain(entity.getCustomer()))
                    .ship(ShipMapper.toDomain(entity.getShip()))
                    .startAt(entity.getStartAt())
                    .endAt(entity.getEndAt())
                    .feeCharged(entity.getFeeCharged())
                    .status(toDomainStatus(entity.getStatus()))
                    .routes(routes)
                    .build();
        }


        */
    /**
     * Mapowanie domena -> encja.
     * Korzystamy z istniejącego konstruktora ReservationEntity(Reservation, StarportEntity),
     * który wewnątrz wykonuje:
     * - setDockingBay(...)
     * - setCustomer(...)
     * - setShip(...)
     * - addRoute(...)
     */
    /*
        static ReservationEntity toEntity(Reservation reservation, StarportEntity starportEntity) {
            if (reservation == null) {
                return null;
            }
            return new ReservationEntity(reservation, starportEntity);
        }


        private static Reservation.ReservationStatus toDomainStatus(ReservationEntity.ReservationStatus status) {
            if (status == null) {
    // domyślnie HOLD, ale możesz dostosować
                return Reservation.ReservationStatus.HOLD;
            }
            return Reservation.ReservationStatus.valueOf(status.name());
        }


        static ReservationEntity.ReservationStatus toEntityStatus(Reservation.ReservationStatus status) {
            if (status == null) {
                return ReservationEntity.ReservationStatus.HOLD;
            }
            return ReservationEntity.ReservationStatus.valueOf(status.name());
        }*/
}
