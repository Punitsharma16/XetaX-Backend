# Multi-stage: build the jar with Maven, run it on a slim JRE 21.
# Build context = the crm/ folder (see docker-compose.yml → build.context: ..).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Cache dependencies first — only re-downloads when pom.xml changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package \
 && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# curl is only for the compose healthcheck (the JRE image ships without it).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 xetax && mkdir -p /app/uploads && chown -R xetax /app
USER xetax
COPY --from=build /app.jar /app/crm.jar
EXPOSE 8085
# -XX:MaxRAMPercentage lets the JVM size itself from the container limit
# (mem_limit in compose) instead of a hard-coded -Xmx.
ENTRYPOINT [
"java",
"-XX:MaxRAMPercentage=70",
"-XX:InitialRAMPercentage=20",
"-XX:+UseG1GC",
"-jar",
"/app/crm.jar"
]