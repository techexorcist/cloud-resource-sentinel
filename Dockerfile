FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Layer 1: Maven wrapper (rarely changes)
COPY mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw

# Layer 2: Dependencies (changes when pom.xml changes)
COPY pom.xml ./
RUN ./mvnw dependency:resolve -q -B

# Layer 3: Source code (changes most often)
COPY src src
RUN ./mvnw package -DskipTests -q -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Security hardening: run as non-root user
RUN addgroup -g 1000 sentinel && adduser -u 1000 -G sentinel -s /bin/sh -D sentinel
RUN mkdir -p /home/sentinel/.cloud-sentinel/reports /home/sentinel/.cloud-sentinel/audit \
    && chown -R sentinel:sentinel /home/sentinel/.cloud-sentinel

COPY --from=build /app/target/*.jar app.jar
RUN chown sentinel:sentinel app.jar

USER sentinel
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=15s \
    CMD wget -qO- http://localhost:8000/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-Xms256m", "-Xmx512m", \
    "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
