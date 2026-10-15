# 빌드 컨텍스트는 저장소 루트다(bff 가 sdk 모듈에 의존한다): docker build -f bff/Dockerfile .
# 1. 빌드 스테이지
FROM amazoncorretto:25 AS builder

WORKDIR /workspace
RUN yum install -y findutils

COPY gradlew .
COPY gradle gradle
COPY settings.gradle ./
COPY sdk sdk
COPY bff bff
# 루트 settings.gradle 은 auth/guard 도 포함하므로, 이 이미지에서는 bff 와 sdk 만 쓰도록 settings 를 좁힌다.
RUN printf "rootProject.name = 'doro-platform'\ninclude 'sdk'\ninclude 'bff'\n" > settings.gradle \
    && rm -rf sdk/build bff/build \
    && chmod +x ./gradlew && ./gradlew :bff:bootJar -x test --no-daemon

# 2. 런타임 스테이지
FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

RUN addgroup -S doro && adduser -S doro -G doro
USER doro:doro

COPY --from=builder --chown=doro:doro /workspace/bff/build/libs/bff-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8085

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8085/actuator/health || exit 1

# IAM 컨테이너가 교체돼 주소가 바뀌어도 곧 따라가도록 JVM 의 DNS 캐시를 짧게 둔다(기본 30초).
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-Dsun.net.inetaddr.ttl=5", "-Dsun.net.inetaddr.negative.ttl=1", "-jar", "app.jar"]
