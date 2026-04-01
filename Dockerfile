# ═══════════════════════════════════════════════════════════════════
#  Velora — Dockerfile
#  ELI5: A recipe that builds your Spring Boot app into a box
#  (container) that runs anywhere — your laptop, AWS, Railway, etc.
#
#  We use a 2-stage build:
#  Stage 1 (builder) → compile the Java code into a JAR file
#  Stage 2 (runner)  → copy just the JAR, throw away the build tools
#  Result: small final image (~200MB instead of 800MB)
# ═══════════════════════════════════════════════════════════════════

# ── Stage 1: Build ───────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and pom first (Docker caches this layer)
# ELI5: If you only changed Java code (not pom.xml),
# Docker skips re-downloading all dependencies — much faster builds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q

# Now copy source and build
COPY src ./src
RUN ./mvnw clean package -DskipTests -q

# ── Stage 2: Run ────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runner

WORKDIR /app

# Create uploads directory
RUN mkdir -p /app/uploads

# Copy only the JAR from the builder stage
COPY --from=builder /build/target/*.jar app.jar

# Non-root user for security
# ELI5: Running as root inside a container is like leaving your
# front door unlocked. This creates a safe limited user instead.
RUN addgroup -S velora && adduser -S velora -G velora
RUN chown -R velora:velora /app
USER velora

EXPOSE 8001

# Health check — Docker will restart the container if this fails
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8001/api/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]