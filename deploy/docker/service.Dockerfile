# syntax=docker/dockerfile:1.7
# One Dockerfile for every Spring Boot service:
#   docker build -f deploy/docker/service.Dockerfile --build-arg SERVICE=reservation-service -t railops/reservation-service .
# Base images pinned by digest (resolved 2026-11-12); bump deliberately (Renovate/Dependabot), never implicitly.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515

# ---------------------------------------------------------------------------------------------------------- build
FROM ${MAVEN_IMAGE} AS build
ARG SERVICE
WORKDIR /src
# Poms first: dependency resolution is cached until a pom changes.
COPY pom.xml .
COPY railops-common/pom.xml railops-common/
COPY railops-test-support/pom.xml railops-test-support/
COPY reservation-service/pom.xml reservation-service/
COPY identity-service/pom.xml identity-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/
COPY api-gateway/pom.xml api-gateway/
COPY tools/local-kafka/pom.xml tools/local-kafka/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -pl ${SERVICE} -am dependency:go-offline -DexcludeReactor=true || true
COPY . .
# Tests run in CI before the image is built (ci.yml); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -pl ${SERVICE} -am package -DskipTests \
 && java -Djarmode=tools -jar ${SERVICE}/target/${SERVICE}-*.jar extract --layers --launcher --destination /layers

# -------------------------------------------------------------------------------------------------------- runtime
FROM ${RUNTIME_IMAGE}
ARG SERVICE
LABEL org.opencontainers.image.title="railops-${SERVICE}" \
      org.opencontainers.image.source="https://github.com/railops/railops"
# Fixed non-root uid/gid so Kubernetes runAsNonRoot/runAsUser and file ownership line up.
RUN groupadd --system --gid 10001 railops && useradd --system --uid 10001 --gid railops --no-create-home railops
WORKDIR /opt/app
# Least-changing layer first: a code-only release re-pushes ~1 MB, not ~70 MB.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
COPY --chmod=0755 deploy/docker/healthcheck.sh /opt/app/healthcheck.sh
# - container-aware heap (75% of the limit), metaspace bounded, crash instead of limping on OOM
# - UTC: run dates are LocalDate; never let the host zone leak in (Phase 5: Asia/Calcutta broke PostgreSQL)
# - tmpdir on the writable /tmp mount: the root filesystem is read-only in Kubernetes
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:MaxMetaspaceSize=256m \
-XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8" \
    SERVER_PORT=8080
USER 10001:10001
EXPOSE 8080
# Compose only; Kubernetes uses httpGet probes on /actuator/health/{liveness,readiness}.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 CMD ["/opt/app/healthcheck.sh"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
