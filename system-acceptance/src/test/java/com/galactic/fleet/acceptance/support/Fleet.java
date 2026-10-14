package com.galactic.fleet.acceptance.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The only door into the system under test. Everything goes over the wire — no production class is
 * on the classpath — so the same suite runs unchanged against three services behind a gateway and
 * against a single modular monolith.
 *
 * <p>Endpoints are configurable so the suite can be pointed at any deployment:
 * {@code -Dfleet.base-url}, {@code -Dfleet.kafka}, {@code -Dfleet.prometheus}.
 */
public final class Fleet {

    public static final String BASE_URL = System.getProperty("fleet.base-url", "http://localhost:8080");
    public static final String KAFKA = System.getProperty("fleet.kafka", "localhost:9092");
    public static final String PROMETHEUS = System.getProperty("fleet.prometheus", "http://localhost:9090");

    public static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Fleet() {}

    /** A response reduced to what a client can observe: status and the parsed JSON body. */
    public record Reply(int status, String rawBody, JsonNode body) {}

    /** Seed data the suite relies on (V2 migration): customer i owns exactly one ship of a known class. */
    public record Vessel(String customerCode, String shipCode, String shipClass) {
        public static final Vessel SCOUT = new Vessel("CUST-001", "X-WING-001", "SCOUT");
        public static final Vessel FREIGHTER = new Vessel("CUST-002", "FALCON-002", "FREIGHTER");
        public static final Vessel CRUISER = new Vessel("CUST-003", "STAR-DESTROYER-003", "CRUISER");
    }

    public static Reply reserve(String starportCode, String jsonBody) {
        return post("/api/v1/starports/" + starportCode + "/reservations", jsonBody);
    }

    public static Reply post(String path, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return send(request);
    }

    public static Reply get(String absoluteUrl) {
        return send(HttpRequest.newBuilder(URI.create(absoluteUrl))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build());
    }

    private static Reply send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode parsed = null;
            if (raw != null && !raw.isBlank()) {
                try {
                    parsed = JSON.readTree(raw);
                } catch (IOException notJson) {
                    parsed = null;
                }
            }
            return new Reply(response.statusCode(), raw, parsed);
        } catch (IOException e) {
            throw new AssertionError("System under test is not reachable at " + request.uri() + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while calling " + request.uri(), e);
        }
    }

    /**
     * A start instant nobody else uses: a random day roughly 80–350 years ahead, at the given UTC hour.
     * Reservations only collide when windows overlap on the same bay, so a random far-future day keeps
     * tests independent of each other and of previous runs against the same database.
     */
    public static Instant freshSlotAt(int utcHour) {
        long days = ThreadLocalRandom.current().nextLong(30_000, 128_000);
        return Instant.now().truncatedTo(ChronoUnit.DAYS).plus(days, ChronoUnit.DAYS).plus(utcHour, ChronoUnit.HOURS);
    }

    /** Request body for a reservation; {@code originPortId == null} means "no route requested". */
    public static String reservationBody(Vessel vessel, Instant startAt, long hours, String originPortId) {
        boolean requestRoute = originPortId != null;
        return """
                {
                  "customerCode": "%s",
                  "shipCode": "%s",
                  "shipClass": "%s",
                  "startAt": "%s",
                  "endAt": "%s",
                  "requestRoute": %s,
                  "originPortId": %s
                }
                """
                .formatted(
                        vessel.customerCode(),
                        vessel.shipCode(),
                        vessel.shipClass(),
                        startAt,
                        startAt.plus(hours, ChronoUnit.HOURS),
                        requestRoute,
                        requestRoute ? "\"" + originPortId + "\"" : "null");
    }
}
