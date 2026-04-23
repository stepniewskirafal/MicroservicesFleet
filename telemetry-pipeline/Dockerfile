# ─── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy root POM and all module POMs for dependency caching
COPY pom.xml ./
COPY eureka-server/pom.xml ./eureka-server/
COPY api-gateway/pom.xml ./api-gateway/
COPY starport-registry/pom.xml ./starport-registry/
COPY trade-route-planner/pom.xml ./trade-route-planner/
COPY telemetry-pipeline/pom.xml ./telemetry-pipeline/

RUN mvn -pl telemetry-pipeline -am dependency:go-offline -q

# Copy source and build
COPY telemetry-pipeline/src ./telemetry-pipeline/src
RUN mvn -pl telemetry-pipeline -am package -DskipTests -q

# ─── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

COPY --from=build /build/telemetry-pipeline/target/telemetry-pipeline-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
