# Imagen de producción del backend (multi-etapa, capas de Spring Boot, usuario sin privilegios).
#   docker build -t canchatrack-backend backend
# El perfil por defecto es `prod`: sin DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET, MFA_ENCRYPTION_KEY, MAIL_HOST y MAIL_FROM no arranca (a propósito).

# ---- Etapa 1: compilar y extraer capas ------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Primero solo el pom: la capa de dependencias se reutiliza mientras no cambien.
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline

COPY src ./src
# Las pruebas se ejecutan en el pipeline de CI (mvn verify); aquí solo se empaqueta.
RUN mvn -B -q -ntp -DskipTests package \
    && cp target/canchatrack-backend-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Etapa 2: ejecución ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S canchatrack && adduser -S -G canchatrack canchatrack \
    && mkdir -p /data/files && chown -R canchatrack:canchatrack /data
WORKDIR /app

# De lo que menos cambia a lo que más cambia, para aprovechar la caché de capas.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER canchatrack
ENV SPRING_PROFILES_ACTIVE=prod \
    CANCHATRACK_FILES_DIRECTORY=/data/files \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME /data/files
EXPOSE 8080

# Sondas para el orquestador: /actuator/health/liveness y /actuator/health/readiness.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
