FROM maven:3.9.12-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S careerlens && adduser -S careerlens -G careerlens
RUN mkdir -p /app/data && chown careerlens:careerlens /app/data
COPY --from=build /workspace/target/core-api-*.jar /app/core-api.jar
USER careerlens
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=4s --start-period=20s --retries=5 CMD wget -q -O - http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/core-api.jar"]
