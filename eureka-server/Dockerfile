# --- build stage: Maven w obrazie ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# kopiujemy cały monorepo (albo zoptymalizuj przez .dockerignore)
COPY . .
# budujemy wyłącznie eureka-server (+ potrzebne zależności z -am)
RUN mvn -q -DskipTests -pl eureka-server -am package

# --- run stage: lekki JRE ---
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl do healthchecków/debugu
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/eureka-server/target/eureka-server-*.jar /app/app.jar
EXPOSE 8761
ENTRYPOINT ["java","-jar","/app/app.jar"]
