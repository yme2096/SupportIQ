# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# Production images need the main artifact only. Keep Maven's dependency cache across BuildKit rebuilds;
# the Compose test profile runs the full test lifecycle separately.
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/supportiq-1.0.0.jar app.jar
COPY --chmod=755 render-start.sh /app/render-start.sh
RUN useradd --system --uid 10001 --create-home --home-dir /home/supportiq --shell /usr/sbin/nologin supportiq
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/render-start.sh"]
