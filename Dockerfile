# build stage (आपका मौजूदा)
FROM gradle:8.4.0-jdk21-alpine AS build
WORKDIR /app
COPY build.gradle settings.gradle /app/
COPY gradlew /app/gradlew
COPY gradle /app/gradle
RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon
COPY src /app/src
RUN ./gradlew build --no-daemon -x test

# runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG JAR_FILE=/app/build/libs/*.jar
COPY --from=build ${JAR_FILE} /app/app.jar

# Optional default PORT (Render sets this at runtime; this just documents a default)
ENV PORT=10000

# Expose is informative only
EXPOSE 8080 10000

# Use sh -c so $PORT expands; Spring Boot will use --server.port=$PORT
ENTRYPOINT ["sh","-c","exec java -jar /app/app.jar --server.port=$PORT"]
