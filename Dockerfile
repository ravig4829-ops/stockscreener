# Start with a Gradle image as the build stage
FROM gradle:8.4.0-jdk17-alpine AS build

# Set the working directory
WORKDIR /app

# Copy the build configuration files first to leverage caching
# This assumes your project has a settings.gradle and build.gradle at the root
COPY build.gradle settings.gradle /app/

# Copy the Gradle wrapper files (important for consistent builds)
COPY gradlew /app/gradlew
COPY gradle /app/gradle

# Download dependencies
# The --no-daemon flag prevents the Gradle daemon from running, which is ideal for CI/CD environments
RUN ./gradlew dependencies --no-daemon
# Copy the source code
COPY src /app/src

# Run the build, creating the fat JAR file
RUN ./gradlew build --no-daemon

############################
# 2) Run-time stage
############################
# Use a lightweight JRE base image for the final image
FROM eclipse-temurin:17-jre-alpine

# Set the working directory
WORKDIR /app
# Copy the packaged JAR file from the build stage
# Gradle places the JAR in `build/libs/` by default
ARG JAR_FILE=/app/build/libs/*.jar
COPY --from=build ${JAR_FILE} /app/app.jar

# Expose the application's port
EXPOSE 8080

# Command to run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
