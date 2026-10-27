# syntax=docker/dockerfile:1.7
ARG JAVA_IMAGE=eclipse-temurin:21-jre-alpine

FROM --platform=$BUILDPLATFORM ${JAVA_IMAGE} AS extract
WORKDIR /workspace
COPY target/finsecure-pay.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted \
    && mkdir -p extracted/snapshot-dependencies

FROM ${JAVA_IMAGE}
ARG VERSION=dev
ARG REVISION=unknown
ARG CREATED=unknown
LABEL org.opencontainers.image.title="finsecure-pay" \
      org.opencontainers.image.description="FinSecure Pay transaction validation API" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.source="https://github.com/mauyrint/finsecure-pay"

RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && addgroup -S -g 10001 finsecure \
    && adduser -S -u 10001 -G finsecure -h /app finsecure

WORKDIR /app
COPY --from=extract --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=extract --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=10001:10001 /workspace/extracted/application/ ./

ENV APP_VERSION=${VERSION} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

USER 10001:10001
EXPOSE 8080 8081

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
