# Simple runtime image - uses pre-built jar
# Build jar first: mvn clean package -DskipTests
# Using eclipse-temurin (Ubuntu-based) with required tools

FROM eclipse-temurin:21-jre
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

# Arthas: mount at runtime via volume instead of baking into image
# docker run -v /path/to/arthas:/opt/arthas ...

# Create non-root user for security
RUN groupadd -r gateway && useradd -r -g gateway -d /home/gateway gateway

# Create home directory for gateway user (needed by Nacos client cache)
RUN mkdir -p /home/gateway && chown -R gateway:gateway /home/gateway

# Copy pre-built jar (build locally first)
COPY target/my-gateway-1.0.0.jar app.jar

# Set ownership
RUN chown -R gateway:gateway /app

# Switch to non-root user
USER gateway

# Expose port. application.yml defaults SERVER_PORT to 80, but this image runs as the
# non-root `gateway` user and cannot bind a privileged port without CAP_NET_BIND_SERVICE,
# so pin an unprivileged default here. Override SERVER_PORT to change it.
ENV SERVER_PORT=8080
EXPOSE 8080

# JVM options for container environment.
# FIX: this used to set both -Xmx512m and -XX:MaxRAMPercentage=75.0. An explicit -Xmx wins
# outright, so the percentage was dead text and the heap ignored the container limit - the
# worst of both. Keep only the container-aware setting.
ENV JAVA_OPTS="-Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=3 CMD curl -f http://localhost:8080/actuator/health || exit 1

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]