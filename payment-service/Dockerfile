# syntax=docker/dockerfile:1
#
# payment-service image. Two stages: a full JDK + Maven to build, a JRE-only Alpine image to run.
# The runtime image carries no compiler, no Maven, no source and no ~/.m2 - only what the process
# needs, which is both a smaller attack surface and a smaller pull.
#
#   docker build -t payment-service ./payment-service
#
# Usually you do not run this directly: `docker compose up` at the repository root builds it.

# ---------------------------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# The POM first, on its own layer. Dependencies change far less often than source, so a code-only
# change reuses this layer and does not re-download the internet. The cache mount keeps ~/.m2
# between builds on the same machine without baking it into any image layer.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests are not run in the image build because CI runs `mvn verify` (unit + Testcontainers ITs) on every
# push. Running Testcontainers inside a docker build would need the Docker socket in the build,
# which is the wrong trade for an image recipe.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && java -Djarmode=tools -jar target/payment-service-*.jar extract --layers --launcher --destination /build/extracted

# ---------------------------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# A fixed, unprivileged user. If the process is ever compromised it is not root in the container,
# and it cannot write over its own application files (they are owned by root, read-only to app).
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -H -s /sbin/nologin app
WORKDIR /app

# Spring Boot's layered jar, copied most-stable layer first. A code change rebuilds
# only the last few-hundred-KB layer; the ~60 MB of third-party jars underneath stays cached.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER app
EXPOSE 8080

# Size the heap from the container's memory limit, not the host's. Without this the JVM sees the
# whole machine and a memory-limited pod gets OOM-killed instead of throwing OutOfMemoryError.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Exec form, so java is PID 1 and receives SIGTERM directly - which is what lets
# `server.shutdown: graceful` finish in-flight requests on `docker compose stop`.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
