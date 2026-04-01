# ═══════════════════════════════════════════════════════════════════
#  Velora — Production Dockerfile (Render Ready)
# ═══════════════════════════════════════════════════════════════════

# ── Stage 1: Build ───────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy only pom first (for caching dependencies)
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests

# ── Stage 2: Run ────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runner

WORKDIR /app

# Create uploads directory
RUN mkdir -p /app/uploads

# Copy built JAR
COPY --from=builder /build/target/*.jar app.jar

# Create non-root user
RUN addgroup -S velora && adduser -S velora -G velora
RUN chown -R velora:velora /app
USER velora

# Render uses dynamic PORT → we expose 8080 (fallback)
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/actuator/health || exit 1

# Run app
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]