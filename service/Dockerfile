# syntax=docker/dockerfile:1.6

FROM maven:3-eclipse-temurin-25-alpine AS build
WORKDIR /app

# Resolve dependencies first so the layer caches when only sources change.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run as non-root.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build --chown=app:app /app/target/service-0.0.1-SNAPSHOT.jar app.jar

# Debug-agent flags are intentionally NOT set here — opt in per-environment via
# JAVA_TOOL_OPTIONS in the runtime config when a remote debugger is needed.
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
