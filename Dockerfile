# One Dockerfile for all services:  docker build --build-arg MODULE=order-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/
COPY catalog-service/pom.xml catalog-service/
COPY api-gateway/pom.xml api-gateway/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline -DskipTests || true
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:24-jre
ARG MODULE
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/${MODULE}/target/${MODULE}-*-exec.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
