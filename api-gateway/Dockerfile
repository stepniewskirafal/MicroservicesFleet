# ─── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy root POM and all module POMs for dependency caching
COPY pom.xml ./
COPY api-gateway/pom.xml ./api-gateway/
COPY eureka-server/pom.xml ./eureka-server/
COPY starport-registry/pom.xml ./starport-registry/
COPY trade-route-planner/pom.xml ./trade-route-planner/
COPY telemetry-pipeline/pom.xml ./telemetry-pipeline/

RUN mvn -pl api-gateway -am dependency:go-offline -q

# Copy source and build
COPY api-gateway/src ./api-gateway/src
RUN mvn -pl api-gateway -am package -DskipTests -q

# ─── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

COPY --from=build /build/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
