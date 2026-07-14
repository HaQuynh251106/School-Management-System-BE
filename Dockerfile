FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY services/app/pom.xml services/app/pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/academic-service/pom.xml services/academic-service/pom.xml
COPY services/finance-service/pom.xml services/finance-service/pom.xml
COPY services/notification-service/pom.xml services/notification-service/pom.xml
COPY services/file-service/pom.xml services/file-service/pom.xml
RUN mvn -B -pl services/app -am dependency:go-offline
COPY common/src common/src
COPY services/app/src services/app/src
RUN mvn -B -pl services/app -am clean package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S sse && adduser -S sse -G sse
WORKDIR /app
COPY --from=build /workspace/services/app/target/sse-app.jar app.jar
USER sse
EXPOSE 4000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
