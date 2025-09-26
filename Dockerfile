# ---- build stage ----
FROM maven:3.9.4-eclipse-temurin-17 as build
WORKDIR /app

# copy maven files first for layer caching
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN mvn -B -f pom.xml -q dependency:go-offline

# copy source and build
COPY src ./src
RUN mvn -B -f pom.xml -q package -DskipTests

# ---- run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# copy jar from build stage (adjust name if your jar has different name)
COPY --from=build /app/target/*.jar app.jar

# expose (not strictly required but useful)
EXPOSE 10000

# Run with PORT environment variable (Render sets PORT; default expected port is 10000)
# we pass server.port from env so Spring binds to the correct port
ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-10000} -Dserver.address=0.0.0.0 -jar /app/app.jar"]
