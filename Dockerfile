# syntax=docker/dockerfile:1

# ---- Build stage: compile the reactor and repackage factory-app's Spring Boot jar ----
# Temurin 21 JDK + Maven 3.9. The project targets Java 21 and commits no Maven wrapper,
# so the build image supplies Maven. -am builds only factory-app and its upstream modules.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy the whole multi-module reactor. .dockerignore keeps host build output (**/target),
# .git and IDE files out of the context so stale/wrong-arch classes never leak in.
COPY . .

# Tests are skipped here on purpose: they use Testcontainers/Docker, which is not available
# inside this build container. CI runs `mvn verify` on the runner instead.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -pl factory-app -am package

# Extract the layered jar (Spring Boot 4.x `tools` jarmode) for optimal Docker layer caching.
RUN cp factory-app/target/factory-app-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime stage: minimal JRE, non-root, actuator healthcheck ----
FROM eclipse-temurin:21-jre AS runtime

# curl is used only by the container HEALTHCHECK; create an unprivileged runtime user.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 spring \
 && useradd --system --uid 1001 --gid spring --home-dir /application --shell /usr/sbin/nologin spring

WORKDIR /application

# Copy the extracted layers slowest-changing first so image layers cache well across builds.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring
EXPOSE 8080

# Honors the Spring Boot Actuator health endpoint already exposed in application.yaml.
# start-period covers Flyway migration + Spring context startup.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# The JVM picks up JAVA_TOOL_OPTIONS automatically, so no shell wrapper is needed.
ENTRYPOINT ["java", "-jar", "application.jar"]
