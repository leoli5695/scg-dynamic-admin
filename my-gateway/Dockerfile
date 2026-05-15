# Simple runtime image - uses pre-built jar
# Build jar first: mvn clean package -DskipTests
# Using eclipse-temurin (Ubuntu-based) with required tools

FROM 05feb4b8a268e111bb3c4356701bf0c4.d.1ms.run/eclipse-temurin:17-jre
WORKDIR /app

# Install useful tools for debugging (Ubuntu-based)
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    tar \
    gzip \
    net-tools \
    procps \
    vim \
    less \
    netcat-openbsd \
    iputils-ping \
    dnsutils \
    lsof \
    strace \
    tcpdump \
    jq \
    file \
    && rm -rf /var/lib/apt/lists/*

# Copy local Arthas package (faster build, no network dependency)
COPY arthas-bin.zip /tmp/arthas.zip
RUN mkdir -p /opt/arthas && \
    unzip -q /tmp/arthas.zip -d /opt/arthas && \
    rm /tmp/arthas.zip && \
    chmod +x /opt/arthas/*.jar

# Create non-root user for security
RUN groupadd -r gateway && useradd -r -g gateway -d /home/gateway gateway

# Create home directory for gateway user (needed by Nacos client cache)
RUN mkdir -p /home/gateway && chown -R gateway:gateway /home/gateway

# Copy pre-built jar (build locally first)
COPY target/my-gateway-1.0.0.jar app.jar

# Set ownership (including Arthas directory)
RUN chown -R gateway:gateway /app /opt/arthas

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