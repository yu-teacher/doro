FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

# Non-root 사용자 생성
RUN addgroup -S doro && adduser -S doro -G doro
USER doro:doro

COPY --chown=doro:doro build/libs/auth-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
