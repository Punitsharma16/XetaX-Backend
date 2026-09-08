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
# JVM sizing lives in docker-compose.yml (JAVA_TOOL_OPTIONS), next to the
# container's mem_limit — one place to tune, no conflicting flags here.
ENTRYPOINT ["java", "-jar", "/app/crm.jar"]
