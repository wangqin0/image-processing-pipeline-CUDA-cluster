# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build  
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q package -DskipTests

# ---------- runtime stage ----------
FROM openjdk:17-jdk
WORKDIR /app
COPY --from=build /build/target/scheduler-0.0.1-SNAPSHOT.jar scheduler.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/scheduler.jar"]