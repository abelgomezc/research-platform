# Etapa 1: dependencias
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Etapa 2: empaquetado
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY --from=deps /root/.m2 /root/.m2
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests clean package

# Etapa 3: ejecucion minima sin herramientas de build
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Usuario sin privilegios
RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /build/target/research-platform-backend-*.jar app.jar
RUN chown app:app /app/app.jar
USER app

EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "wget -qO- http://localhost:${SERVER_PORT:-8081}/actuator/health/liveness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
