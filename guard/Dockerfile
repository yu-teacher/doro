# Build stage
FROM amazoncorretto:25 AS builder
WORKDIR /workspace
RUN yum install -y findutils

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# Run stage
FROM amazoncorretto:25-alpine
WORKDIR /app

RUN addgroup -S doro && adduser -S doro -G doro
USER doro:doro

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8081 9090

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

