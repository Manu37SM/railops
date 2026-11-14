# syntax=docker/dockerfile:1.7
# One Dockerfile for every Spring Boot service:
#   docker build -f deploy/docker/service.Dockerfile --build-arg SERVICE=reservation-service -t railops/reservation-service .
# Base images pinned by digest (resolved 2026-11-14); bump deliberately (Renovate/Dependabot), never implicitly.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25-noble@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c

# ---------------------------------------------------------------------------------------------------------- build
# The image is pinned by tag AND sha256 digest in the ARG above; hadolint cannot see through ${...}.
# hadolint ignore=DL3006
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
 && java -Djarmode=tools -jar ${SERVICE}/target/${SERVICE}-*.jar extract --layers --application-filename app.jar --destination /layers

# -------------------------------------------------------------------------------------------------------- runtime
# The image is pinned by tag AND sha256 digest in the ARG above; hadolint cannot see through ${...}.
# hadolint ignore=DL3006
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
# AOT cache (JDK 25): classes already loaded and linked, plus method profiles, from one training start, so the JVM
# skips most of that work on every start. Measured in the same image layout on one CPU core (see DEPLOYMENT.md).
# Matters on Render's free plan, where services start on every wake-up. The training run starts the app once and
# exits right after the context refreshes, before the web server, Kafka listeners or schedulers start. There is no
# database or Kafka during the image build, so the training-only settings below skip every connection. It must be
# made by this image's own JVM, hence here and not in the build stage. Serial GC like on Render. If training fails
# the build carries on without a cache, and the JVM ignores a missing or unusable cache (it only logs a line), so
# the worst case is the old startup time.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -XX:+UseSerialGC \
      -jar app.jar --spring.profiles.active=local --spring.flyway.enabled=false --spring.jpa.hibernate.ddl-auto=none \
      --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      --spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      --spring.datasource.url=jdbc:postgresql://aot-training.invalid:5432/x \
      --spring.kafka.admin.auto-create=false --spring.kafka.bootstrap-servers=aot-training.invalid:9092 \
      > /tmp/aot-training.log 2>&1 \
 || { echo "AOT training failed; image will start without the cache"; tail -n 20 /tmp/aot-training.log; }; \
    rm -f /tmp/aot-training.log
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
ENTRYPOINT ["java", "-jar", "app.jar"]
