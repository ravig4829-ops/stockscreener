# ---- build stage ----
FROM maven:3.9.4-eclipse-temurin-17 AS build

WORKDIR /app

# सिर्फ pom.xml कॉपी करके डिपेंडेंसीज़ डाउनलोड करवाएँ
COPY build.gradle .

# src फोल्डर कॉपी करें
COPY src ./src

# पैकेजिंग (टेस्ट स्किप करके)
RUN mvn clean package -DskipTests

############################
# 2) रन टाइम स्टेज (JDK24)
############################
# Alpine बेस पर Temurin 24 JDK यूज़ करें
FROM eclipse-temurin:17-jdk-alpine

# बिल्ड स्टेज से JAR कॉपी करें
ARG JAR_FILE=/app/target/*.jar
COPY --from=build ${JAR_FILE} /app/app.jar

# पोर्ट अगर एक्सपोज करना हो तो
EXPOSE 8080

# कंटेनर स्टार्ट कमांड
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
