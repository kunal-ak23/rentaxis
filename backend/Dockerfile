FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy build files first for better layer caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source and build
COPY src src
RUN ./gradlew build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Run as non-root user
RUN addgroup --system --gid 1001 spring && \
    adduser --system --uid 1001 spring

COPY --from=builder /app/build/libs/*.jar app.jar

# Create data directory for contract PDFs (writable by spring user)
RUN mkdir -p /app/data && chown spring:spring /app/data

USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
