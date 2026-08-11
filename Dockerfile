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
COPY docs/openapi docs/openapi
RUN mvn -B -pl services/app -am clean package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system sse \
    && useradd --system --gid sse --home-dir /app sse \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl gosu \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/logs /app/data/uploads \
    && chown -R sse:sse /app
WORKDIR /app
COPY --from=build /workspace/services/app/target/sse-app.jar app.jar
COPY infrastructure/docker/backend-entrypoint.sh /usr/local/bin/backend-entrypoint
RUN chmod +x /usr/local/bin/backend-entrypoint
USER root
EXPOSE 4000
ENTRYPOINT ["backend-entrypoint"]
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
