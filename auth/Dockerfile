# 1. 빌드 스테이지
FROM amazoncorretto:25 AS builder

WORKDIR /app
RUN yum install -y findutils

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

# 실행 권한 부여 및 빌드
RUN chmod +x ./gradlew && ./gradlew bootJar -x test --no-daemon

# 2. 런타임 스테이지
FROM amazoncorretto:25-alpine

WORKDIR /app

# Non-root 사용자 생성
RUN addgroup -S doro && adduser -S doro -G doro
USER doro:doro

COPY --from=builder --chown=doro:doro /app/build/libs/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

