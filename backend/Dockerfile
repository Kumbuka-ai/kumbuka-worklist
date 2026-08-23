# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# The worklist service — multi-stage build.
#
# stage 1  Maven and a JDK build the Quarkus fast-jar. Only the POM is copied
#          first, so the dependency layer survives a source change that does
#          not touch dependencies.
# stage 2  A JRE carrying the fast-jar payload and nothing else.
#
# The image contains no credential and no hostname. Everything the service
# needs to reach a database or an identity provider arrives as an environment
# variable at run time — see application.properties for the WORKLIST_* names.
# ---------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /work

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline -DskipTests || true

COPY src src

# Tests are not run here. They need a PostgreSQL and a Keycloak container, so
# running them inside an image build would mean either docker-in-docker or a
# quietly reduced suite. The pipeline runs the full suite before the image is
# built, which is the arrangement that keeps the suite whole.
RUN mvn -B -ntp package -DskipTests

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /work/target/quarkus-app/lib/     /app/lib/
COPY --from=build /work/target/quarkus-app/*.jar    /app/
COPY --from=build /work/target/quarkus-app/app/     /app/app/
COPY --from=build /work/target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080
ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en
USER 1000

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
