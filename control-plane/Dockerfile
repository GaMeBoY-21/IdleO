# The control plane's image (ADR 0045): built with Maven, run from Spring Boot's layered jar on Eclipse Temurin's JRE,
# on a base with no shell and no package manager. It runs as 10001 on a read-only root (the chart mounts a tmpfs at
# /tmp, Tomcat's and the JVM's scratch), and sizes its heap from the container's memory limit.
#
#   docker build -f control-plane/Dockerfile -t idleo-control-plane .     (from the repository root: the build reads
#                                                                           proto/, templates/ and api/ too)
#
# There's no HEALTHCHECK: without a shell or an HTTP client in the image, one would start a second JVM every few
# seconds. Kubernetes' probes on /actuator/health/liveness and /readiness are the health check (deploy/helm/idleo).

# The build: Temurin's JDK 25 with the Maven release ./mvnw pins (.mvn/wrapper/maven-wrapper.properties; the pom's
# enforcer refuses any other).
FROM maven:3.9.16-eclipse-temurin-25-noble@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
WORKDIR /src/control-plane
COPY control-plane/pom.xml ./
# Dependencies first, in their own cached step, so a source change doesn't download them again.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -DexcludeReactor=true
COPY proto /src/proto
COPY templates/catalog.yaml /src/templates/catalog.yaml
COPY api/endings.json api/cuda-drivers.json /src/api/
COPY control-plane/src src
# The tests run in CI's control-plane workflow, not here.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -Dmaven.test.skip=true -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/control-plane-*.jar extract --layers --destination /layers \
    && mv /layers/application/control-plane-*.jar /layers/application/control-plane.jar

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f AS jre

# glibc, libstdc++ and CA certificates, and nothing else: no shell, no package manager.
FROM gcr.io/distroless/cc-debian13:nonroot@sha256:3ee00291cf386ec52a552e6a858d555debd74776eb4bfa53fa3df2cd6677515e
ARG VERSION=development
ARG REVISION=unknown
LABEL org.opencontainers.image.title="idleo-control-plane" \
      org.opencontainers.image.description="IdleO's control plane" \
      org.opencontainers.image.source="https://github.com/GaMeBoY-21/IdleO" \
      org.opencontainers.image.licenses="LicenseRef-IdleO-All-Rights-Reserved" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
WORKDIR /app
# Least-changing layer first: a release that changes only our code ships one small layer.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001:10001
ENV JAVA_HOME=/opt/java/openjdk IDLEO_RELEASE=${VERSION}
# The web API, the agents' gRPC port and the scanner's internal API.
EXPOSE 8080 9443 8095
# The heap is 65% of the container's limit: the rest is Netty's direct buffers, metaspace and threads. Out of memory,
# the JVM exits and Kubernetes restarts it, instead of limping on.
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=65", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp"]
# The control plane. The chart's migration Job runs `-cp /app/control-plane.jar com.idleo.migration.DatabaseMigration`
# instead, and an operator command `-jar /app/control-plane.jar <command>` on the ops profile.
CMD ["-jar", "/app/control-plane.jar"]
