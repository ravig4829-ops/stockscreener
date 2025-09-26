############################
# 1) Build stage
############################
FROM gradle:8.4.0-jdk21-alpine AS build

# Set the working directory
WORKDIR /app

# Copy the build configuration files and Gradle wrapper
COPY build.gradle settings.gradle /app/
COPY gradlew /app/gradlew
COPY gradle /app/gradle

# Grant execute permissions to the Gradle wrapper script
RUN chmod +x ./gradlew

# Download dependencies
RUN ./gradlew dependencies --no-daemon

# Copy the application source code
COPY src /app/src

# Run the build, creating the fat JAR file
RUN ./gradlew build --no-daemon -x test

############################
# 2) Run-time stage
############################
FROM eclipse-temurin:21-jre-alpine

# Set the working directory
WORKDIR /app

# Copy the packaged JAR file from the build stage
ARG JAR_FILE=/app/build/libs/*.jar
COPY --from=build ${JAR_FILE} /app/app.jar

# Expose the application's port
EXPOSE 8080

# Command to run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]


