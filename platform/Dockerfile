# One build for all four services: docker build --build-arg MODULE=claims-service .
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

FROM eclipse-temurin:21.0.8_9-jre-alpine
ARG MODULE
RUN adduser -D app
USER app
COPY --from=build /src/${MODULE}/target/app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
