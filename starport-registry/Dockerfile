# --- build stage: Maven + JDK ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# (1) najpierw same POM-y -> cache zależności
COPY pom.xml ./pom.xml
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY starport-registry/pom.xml starport-registry/pom.xml

# pobierz zależności modułu (cache-friendly)
RUN mvn -q -DskipTests -pl starport-registry -am dependency:go-offline

# (2) dopiero teraz reszta źródeł
COPY . .

# build tylko modułu starport-registry (+ -am dla zależności)
RUN mvn -q -DskipTests -pl starport-registry -am package

# --- run stage: lekki JRE ---
FROM eclipse-temurin:21-jre
WORKDIR /app
# pomocniczo curl do debug/healthchecków
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/starport-registry/target/starport-registry-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=docker"]
