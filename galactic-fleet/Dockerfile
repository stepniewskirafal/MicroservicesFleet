# ─── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Harden Maven downloads against flaky networks: retry truncated transfers
# (fixes "Premature end of Content-Length delimited message body" from Maven Central)
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true"

# Copy the POMs first so the dependency layer is cached until a POM changes
COPY pom.xml ./
COPY galactic-fleet/pom.xml ./galactic-fleet/

RUN mvn -pl galactic-fleet -am dependency:go-offline -q

# Copy source and build
COPY galactic-fleet/src ./galactic-fleet/src
RUN mvn -pl galactic-fleet -am package -DskipTests -q

# ─── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

COPY --from=build /build/galactic-fleet/target/galactic-fleet-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
