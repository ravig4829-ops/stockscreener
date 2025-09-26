############################
# 1) Build stage
############################
FROM gradle:8.4.0-jdk21-alpine AS build

# Build-time arg: set this from Render build env (or docker build --build-arg ...)
ARG SPRING_REDIS_URL
# Make it available as env var during build so Gradle tests can read it
ENV SPRING_REDIS_URL=${SPRING_REDIS_URL}

# Working directory
WORKDIR /app

# Copy the build configuration files and Gradle wrapper
COPY build.gradle settings.gradle /app/
COPY gradlew /app/gradlew
COPY gradle /app/gradle

# Grant execute permissions to the Gradle wrapper script
RUN chmod +x ./gradlew

# Download dependencies (warm cache)
RUN ./gradlew dependencies --no-daemon

# Copy source
COPY src /app/src

# Run the build with verbose output and show test reports on failure (temporary debug-friendly)
# Note: This will print stacktraces and tail xml test reports to build logs if tests fail.
RUN ./gradlew build --no-daemon --stacktrace --info --warning-mode all || ( \
    echo "==== BUILD FAILED: printing test artifacts (if any) ====" && \
    if [ -d build/test-results/test ]; then ls -la build/test-results/test || true; for f in build/test-results/test/*.xml; do echo "==== $f ===="; tail -n 200 $f || true; done; fi; \
    echo "==== END OF TEST REPORTS ===="; false )

############################
# 2) Run-time stage
############################
FROM eclipse-temurin:21-jre-alpine

# Set the working directory
WORKDIR /app

# Copy the packaged JAR file from the build stage (wildcard)
ARG JAR_FILE=/app/build/libs/*.jar
COPY --from=build ${JAR_FILE} /app/app.jar

# Expose port
EXPOSE 8080

# Recommended: pass runtime SPRING_REDIS_URL via environment in your platform (Render)
# ENTRYPOINT
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

