package com.galactic.fleet.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.galactic.fleet.acceptance.support.Fleet;
import com.galactic.fleet.acceptance.support.Fleet.Reply;
import com.galactic.fleet.acceptance.support.Fleet.Vessel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The public HTTP contract: {@code POST /api/v1/starports/{code}/reservations} — the only endpoint a
 * client of the fleet can reach. Every assertion is something a caller can observe in the response.
 */
@DisplayName("Reservation API: POST /api/v1/starports/{code}/reservations")
class ReservationApiSystemTest {

    @Nested
    @DisplayName("a valid request")
    class ValidRequest {

        @Test
        @DisplayName("without a route returns 201 with the reserved bay and no route")
        void reserves_a_bay_without_route() {
            Instant startAt = Fleet.freshSlotAt(20);

            Reply reply = Fleet.reserve("ALDER", Fleet.reservationBody(Vessel.SCOUT, startAt, 2, null));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
            JsonNode body = reply.body();
            assertThat(body.path("reservationId").asLong()).isPositive();
            assertThat(body.path("starportCode").asText()).isEqualTo("ALDER");
            assertThat(body.path("bayNumber").asText()).isNotBlank();
            assertThat(Instant.parse(body.path("startAt").asText())).isEqualTo(startAt);
            assertThat(Instant.parse(body.path("endAt").asText())).isEqualTo(startAt.plus(2, ChronoUnit.HOURS));
            assertThat(body.path("feeCharged").isNumber()).isTrue();
            assertThat(body.path("route").isNull() || body.path("route").isMissingNode())
                    .as("no route was requested")
                    .isTrue();
        }

        @Test
        @DisplayName("with a route returns 201 with the planned route attached")
        void reserves_a_bay_with_route() {
            Reply reply =
                    Fleet.reserve("HOTH", Fleet.reservationBody(Vessel.FREIGHTER, Fleet.freshSlotAt(20), 1, "TATOO"));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
            JsonNode route = reply.body().path("route");
            assertThat(route.path("routeCode").asText()).matches("ROUTE-[0-9A-F]{8}");
            assertThat(route.path("startStarportCode").asText()).isEqualTo("TATOO");
            assertThat(route.path("destinationStarportCode").asText()).isEqualTo("HOTH");
            assertThat(route.path("riskScore").asDouble()).isBetween(0.0, 1.0);
        }

        @ParameterizedTest(name = "{0}: ETA between {1} h and {2} h")
        @MethodSource("etaBands")
        @DisplayName("the route ETA stays in the band of the ship class (base + risk x 10 h)")
        void route_eta_stays_in_the_ship_class_band(Vessel vessel, double min, double max) {
            Reply reply = Fleet.reserve("ENDOR", Fleet.reservationBody(vessel, Fleet.freshSlotAt(20), 1, "NABOO"));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
            assertThat(reply.body().path("route").path("etaHours").asDouble()).isBetween(min, max);
        }

        static Stream<Arguments> etaBands() {
            return Stream.of(
                    Arguments.of(Vessel.SCOUT, 8.0, 18.0),
                    Arguments.of(Vessel.FREIGHTER, 18.0, 28.0),
                    Arguments.of(Vessel.CRUISER, 12.0, 22.0));
        }

        @Test
        @DisplayName("the risk of a corridor is deterministic and the same in both directions")
        void corridor_risk_is_deterministic_and_symmetric() {
            double there = riskOf("DAGO", "BESPIN");
            double thereAgain = riskOf("DAGO", "BESPIN");
            double back = riskOf("BESPIN", "DAGO");

            assertThat(thereAgain).isEqualTo(there);
            assertThat(back).isEqualTo(there);
        }

        private double riskOf(String origin, String destination) {
            Reply reply =
                    Fleet.reserve(destination, Fleet.reservationBody(Vessel.SCOUT, Fleet.freshSlotAt(20), 1, origin));
            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
            return reply.body().path("route").path("riskScore").asDouble();
        }
    }

    @Nested
    @DisplayName("the fee")
    class Fee {

        @ParameterizedTest(name = "{0}")
        @MethodSource("feeCases")
        @DisplayName("is hourly rate x hours, minus long-stay discount, plus peak-arrival surcharge")
        void fee_follows_the_tariff(String rule, Vessel vessel, int startHourUtc, long hours, String expectedFee) {
            Reply reply = Fleet.reserve(
                    "KAMINO", Fleet.reservationBody(vessel, Fleet.freshSlotAt(startHourUtc), hours, null));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(201);
            assertThat(reply.body().path("feeCharged").decimalValue())
                    .as(rule)
                    .isEqualByComparingTo(new BigDecimal(expectedFee));
        }

        static Stream<Arguments> feeCases() {
            return Stream.of(
                    Arguments.of("SCOUT 50/h x 2 h, off-peak arrival", Vessel.SCOUT, 20, 2L, "100"),
                    Arguments.of("FREIGHTER 120/h x 3 h, off-peak arrival", Vessel.FREIGHTER, 20, 3L, "360"),
                    Arguments.of("CRUISER 250/h x 2 h, off-peak arrival", Vessel.CRUISER, 20, 2L, "500"),
                    Arguments.of("peak arrival (06-18 UTC) adds 15%", Vessel.FREIGHTER, 8, 3L, "414"),
                    Arguments.of("arrival at 18:00 UTC is no longer peak", Vessel.FREIGHTER, 18, 3L, "360"),
                    Arguments.of("24 h or more gives 10% discount", Vessel.CRUISER, 20, 24L, "5400"),
                    Arguments.of("72 h or more gives 20% discount", Vessel.SCOUT, 20, 72L, "2880"),
                    Arguments.of("discount and surcharge combine", Vessel.SCOUT, 10, 72L, "3312"));
        }
    }

    @Nested
    @DisplayName("a request that cannot be served")
    class Refusals {

        @Test
        @DisplayName("for an unknown starport returns 404")
        void unknown_starport_returns_404() {
            Reply reply = Fleet.reserve("NOWHERE", Fleet.reservationBody(Vessel.SCOUT, Fleet.freshSlotAt(20), 1, null));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(404);
            assertThat(reply.body().path("details").asText()).isNotBlank();
        }

        @Test
        @DisplayName("for an unknown customer returns 404")
        void unknown_customer_returns_404() {
            Vessel stranger = new Vessel("NOBODY", "X-WING-001", "SCOUT");

            Reply reply = Fleet.reserve("TATOO", Fleet.reservationBody(stranger, Fleet.freshSlotAt(20), 1, null));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(404);
            assertThat(reply.body().path("details").asText()).isNotBlank();
        }

        @Test
        @DisplayName("for an unknown ship returns 404")
        void unknown_ship_returns_404() {
            Vessel ghost = new Vessel("CUST-001", "GHOST-SHIP", "SCOUT");

            Reply reply = Fleet.reserve("TATOO", Fleet.reservationBody(ghost, Fleet.freshSlotAt(20), 1, null));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(404);
            assertThat(reply.body().path("details").asText()).isNotBlank();
        }

        @Test
        @DisplayName("with a missing field returns 422 and names the field")
        void missing_field_returns_422_with_field_message() {
            Instant startAt = Fleet.freshSlotAt(20);
            String body = """
                    {"shipCode":"X-WING-001","shipClass":"SCOUT","startAt":"%s","endAt":"%s","requestRoute":false}"""
                    .formatted(startAt, startAt.plus(1, ChronoUnit.HOURS));

            Reply reply = Fleet.reserve("TATOO", body);

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(422);
            assertThat(reply.body().path("customerCode").asText()).isEqualTo("Customer ID must not be empty");
        }

        @Test
        @DisplayName("starting in the past returns 422 and names the field")
        void start_in_the_past_returns_422() {
            String body = """
                    {"customerCode":"CUST-001","shipCode":"X-WING-001","shipClass":"SCOUT",
                     "startAt":"2020-01-01T00:00:00Z","endAt":"2020-01-01T01:00:00Z","requestRoute":false}""";

            Reply reply = Fleet.reserve("TATOO", body);

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(422);
            assertThat(reply.body().path("startAt").asText()).isEqualTo("Start time must be in the future");
        }

        @Test
        @DisplayName("ending before it starts returns 422")
        void end_before_start_returns_422() {
            Instant startAt = Fleet.freshSlotAt(20);
            String body = """
                    {"customerCode":"CUST-001","shipCode":"X-WING-001","shipClass":"SCOUT",
                     "startAt":"%s","endAt":"%s","requestRoute":false}"""
                    .formatted(startAt, startAt.minus(2, ChronoUnit.HOURS));

            Reply reply = Fleet.reserve("TATOO", body);

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(422);
        }

        @Test
        @DisplayName("with an unknown ship class returns 400 Malformed JSON")
        void unknown_ship_class_returns_400() {
            Vessel battlestar = new Vessel("CUST-001", "X-WING-001", "BATTLESTAR");

            Reply reply = Fleet.reserve("TATOO", Fleet.reservationBody(battlestar, Fleet.freshSlotAt(20), 1, null));

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(400);
            assertThat(reply.body().path("error").asText()).isEqualTo("Malformed JSON");
        }

        @Test
        @DisplayName("with a body that is not JSON returns 400 Malformed JSON")
        void malformed_json_returns_400() {
            Reply reply = Fleet.reserve("TATOO", "{broken json!!!");

            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(400);
            assertThat(reply.body().path("error").asText()).isEqualTo("Malformed JSON");
            assertThat(reply.body().path("details").asText()).isEqualTo("Request body could not be parsed");
        }
    }

    @Nested
    @DisplayName("capacity")
    class Capacity {

        @Test
        @DisplayName("every bay of a class can be booked for the same window; the next request gets 409")
        void booking_beyond_capacity_returns_409() {
            // One starport, one ship class, one identical window nobody else uses: each success takes
            // a different bay until none is left.
            Instant startAt = Fleet.freshSlotAt(20);
            String body = Fleet.reservationBody(Vessel.CRUISER, startAt, 1, null);

            int booked = 0;
            Reply reply = Fleet.reserve("MUSTAF", body);
            while (reply.status() == 201 && booked < 500) {
                booked++;
                reply = Fleet.reserve("MUSTAF", body);
            }

            assertThat(booked).as("number of CRUISER bays at one starport (seed data)").isEqualTo(30);
            assertThat(reply.status()).as(reply.rawBody()).isEqualTo(409);
            assertThat(reply.body().path("details").asText()).isNotBlank();
        }

        @Test
        @DisplayName("a bay is free again for a window that does not overlap")
        void non_overlapping_window_is_still_bookable() {
            Instant startAt = Fleet.freshSlotAt(20);
            Reply first = Fleet.reserve("CORUS", Fleet.reservationBody(Vessel.SCOUT, startAt, 1, null));
            Reply later = Fleet.reserve(
                    "CORUS", Fleet.reservationBody(Vessel.SCOUT, startAt.plus(1, ChronoUnit.HOURS), 1, null));

            assertThat(first.status()).as(first.rawBody()).isEqualTo(201);
            assertThat(later.status()).as(later.rawBody()).isEqualTo(201);
        }
    }
}
