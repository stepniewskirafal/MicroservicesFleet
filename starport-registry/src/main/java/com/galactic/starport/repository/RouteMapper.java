package com.galactic.starport.repository;

final class RouteMapper {

    private RouteMapper() {}

    /*    static Route toDomain(RouteEntity entity) {
            if (entity == null) {
                return null;
            }


    // Uwaga: w domenie Route ma pole Reservation; tutaj świadomie go nie ustawiamy,
    // żeby nie tworzyć cyklicznych grafów. Zwykle wystarczy mieć reservationId
    // lub operować na Route w kontekście Reservation.
            return Route.builder()
                    .id(entity.getId())
                    .routeCode(entity.getRouteCode())
                    .startStarportCode(entity.getStartStarportCode())
                    .destinationStarportCode(entity.getDestinationStarportCode())
                    .etaLightYears(entity.getEtaLightYears())
                    .riskScore(entity.getRiskScore())
    // Brak kolumny is_active w encji -> decyzja domenowa.
    // Tu ustawiamy true, ale możesz to zmienić pod swoje potrzeby.
                    .isActive(true)
                    .build();
        }


        static RouteEntity toEntity(Route route, ReservationEntity reservationEntity) {
            if (route == null) {
                return null;
            }
            return new RouteEntity(route, reservationEntity);
        }


        static List<Route> toDomainList(List<RouteEntity> entities) {
            if (entities == null || entities.isEmpty()) {
                return Collections.emptyList();
            }
            List<Route> routes = new ArrayList<>(entities.size());
            for (RouteEntity entity : entities) {
                routes.add(toDomain(entity));
            }
            return routes;
        }*/
}
