# Build stage
#
# The build context MUST be the repository root - docker-compose.yml sets
# `context: .` with `dockerfile: gateway-admin/Dockerfile`.
#
# Why: gateway-admin inherits gateway-parent (../pom.xml) and depends on the
# internal, unpublished com.leoli:license-client module. With the context limited
# to ./gateway-admin neither was reachable, so `mvn dependency:go-offline` failed
# with "Non-resolvable parent POM" and this image could never be built.
#
# All seven module POMs are copied even though only license-client and
# gateway-admin are compiled: Maven parses the aggregator's full <modules> list
# up front and fails with "Child module ... does not exist" if any is missing.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# POMs first so the dependency layer survives source-only changes.
COPY pom.xml ./
COPY license-client/pom.xml ./license-client/
COPY gateway-trace-starter/pom.xml ./gateway-trace-starter/
COPY license-server/pom.xml ./license-server/
COPY my-gateway/pom.xml ./my-gateway/
COPY gateway-admin/pom.xml ./gateway-admin/
COPY demo-service/pom.xml ./demo-service/
COPY seckill-core-engine/pom.xml ./seckill-core-engine/

# go-offline is best-effort: it cannot resolve reactor-internal artifacts at this
# stage and would otherwise abort the build. `package` below resolves the rest.
RUN mvn -B -pl gateway-admin -am dependency:go-offline -DskipTests || \
    echo "go-offline incomplete (expected for reactor modules); package will resolve the rest"

COPY license-client/src ./license-client/src
COPY gateway-admin/src ./gateway-admin/src
RUN mvn -B -pl gateway-admin -am package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Add non-root user for security
RUN addgroup -g 1000 appuser && \
    adduser -S -u 1000 -G appuser appuser

# Copy built jar
COPY --from=build /app/gateway-admin/target/gateway-admin-*.jar app.jar

# Set ownership
RUN chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# FIX: the ENTRYPOINT used to be exec-form ["java","-jar","/app/app.jar"], which
# never expands $JAVA_OPTS. Any -Xmx / MaxRAMPercentage passed through the
# environment was silently ignored, and with no container memory limit either,
# the JVM sized its heap from the HOST - the classic "compose stack OOMs the
# machine" setup. Shell form + exec makes JAVA_OPTS real and keeps PID 1 signal
# handling correct for graceful shutdown.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=256m -XX:+UseContainerSupport"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
