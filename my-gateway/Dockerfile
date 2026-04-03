# Simple runtime image - uses pre-built jar
# Build jar first: mvn clean package -DskipTests

FROM ghcr.io/graalvm/graalvm-community:17
WORKDIR /app

# Install curl for health check
RUN microdnf install -y curl tzdata && microdnf clean all

# Create non-root user for security
RUN groupadd -r gateway && useradd -r -g gateway gateway

# Copy pre-built jar (build locally first)
COPY target/my-gateway-1.0.0.jar app.jar

# Set ownership
RUN chown -R gateway:gateway /app

# Switch to non-root user
USER gateway

# Expose port
EXPOSE 8080

# JVM options for container environment
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=3 CMD curl -f http://localhost:8080/actuator/health || exit 1

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
