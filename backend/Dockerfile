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

# Patch the base image's OS packages before anything else lands.
#
# A base image is rebuilt on its own schedule, so it routinely carries a package
# whose fix is already published — libexpat 2.8.4-r0 against an available
# 2.8.5-r0 is what prompted this. Upgrading here closes that gap at build time
# instead of waiting for the upstream rebuild.
#
# The cost is that two builds of the same commit can differ in OS package
# versions. That is the right trade for a runtime image: it is rebuilt and
# redeployed anyway, and the alternative is shipping a known-vulnerable package
# because the tag has not moved yet.
RUN apk --no-cache upgrade
WORKDIR /app

COPY --from=build /work/target/quarkus-app/lib/     /app/lib/
COPY --from=build /work/target/quarkus-app/*.jar    /app/
COPY --from=build /work/target/quarkus-app/app/     /app/app/
COPY --from=build /work/target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080
ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en
USER 1000

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
