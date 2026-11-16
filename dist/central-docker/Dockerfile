# svod-engine on fleetq-01 (ADR-0020 S6). Built on the host from `gradlew installDist` output.
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
COPY svod-engine/ /opt/svod-engine/
ENTRYPOINT ["/opt/svod-engine/bin/svod-engine"]
