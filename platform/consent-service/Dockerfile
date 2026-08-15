# =====================================================================================
# UDS Consent & Privacy Control Plane — service image.
#
# Build from the reactor root so the sibling modules are on the context:
#   docker build -f consent-service/Dockerfile -t uds/consent-service:dev .
# =====================================================================================

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

RUN apk add --no-cache maven

# Poms first, so a source-only change does not re-download the dependency tree. The build
# runs on every commit in CI; an eight-minute dependency fetch each time is eight minutes
# nobody waits for, which is how a pipeline stops being run before merge.
COPY pom.xml ./
COPY consent-core/pom.xml consent-core/
COPY consent-ledger/pom.xml consent-ledger/
COPY consent-policy/pom.xml consent-policy/
COPY consent-service/pom.xml consent-service/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY consent-core/src consent-core/src
COPY consent-ledger/src consent-ledger/src
COPY consent-policy/src consent-policy/src
COPY consent-service/src consent-service/src

# Unit tests only. The integration suites need a PostgreSQL container, and Docker-in-Docker
# inside an image build is a dependency this image should not carry — CI runs `mvn verify`
# separately, where the container runtime is available.
RUN mvn -B -q package -DskipTests \
    && cp consent-service/target/*.jar /build/app.jar

# -------------------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root. The process reads a consent ledger and holds the identifier pepper in memory;
# there is no argument for giving it root in its own container.
RUN addgroup -S uds && adduser -S -G uds uds
COPY --from=build --chown=uds:uds /build/app.jar /app/app.jar
USER uds

EXPOSE 8080

# Container memory rather than the host's. Without this the JVM sizes its heap against the
# machine and gets OOM-killed by the orchestrator under load — at which point in-flight
# consent writes are lost and the outbox has to catch up.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Deliberately absent: IDENTIFIER_PEPPER, SNAPSHOT_SIGNING_KEY, and the client passwords.
# The service refuses to start without a pepper, and that refusal is the design working. An
# image carrying a default would produce a database of reversible hashes that nobody
# discovers for a year.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
