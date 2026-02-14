FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -q -B package

FROM tomcat:9.0.108-jdk8-temurin
COPY --from=build /src/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
ENV CATALINA_OPTS="-Xmx160m"
