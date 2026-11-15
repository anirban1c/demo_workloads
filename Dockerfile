# One image recipe for every service. Jars are built on the host first (make build), which keeps the Docker
# context small and reuses the local Gradle cache.
FROM eclipse-temurin:21-jre
ARG SERVICE
RUN useradd --system --uid 10001 app
COPY otel/opentelemetry-javaagent.jar otel/baggage-processor-extension.jar /otel/
COPY services/${SERVICE}/build/libs/app.jar /app/app.jar
USER app
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
