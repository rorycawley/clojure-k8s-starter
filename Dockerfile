# --- Build stage --------------------------------------------------------
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app

COPY deps.edn build.clj ./
RUN clojure -P && clojure -P -T:build

COPY src ./src
COPY resources ./resources

RUN clojure -T:build uber

# --- Runtime stage ------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/registry-api.jar /app/registry-api.jar

ENV PORT=8080
EXPOSE 8080

CMD ["java", "-jar", "registry-api.jar"]
