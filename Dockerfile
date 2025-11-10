# Multi-stage build for Joatse Target Service
FROM amazoncorretto:8 AS builder

# Install Maven
RUN yum update -y && \
    yum install -y wget tar gzip && \
    cd /opt && \
    wget https://archive.apache.org/dist/maven/maven-3/3.9.5/binaries/apache-maven-3.9.5-bin.tar.gz && \
    tar -xzf apache-maven-3.9.5-bin.tar.gz && \
    ln -s apache-maven-3.9.5 maven && \
    rm apache-maven-3.9.5-bin.tar.gz && \
    yum clean all

# Set Maven environment
ENV MAVEN_HOME=/opt/maven
ENV PATH=$PATH:$MAVEN_HOME/bin

# Set working directory
WORKDIR /app

# Copy pom.xml for dependency caching
COPY pom.xml ./

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage
FROM amazoncorretto:8

# Add metadata
LABEL maintainer="Joatse Target"
LABEL description="Joatse Target Service - Tunnel Client"

# Set working directory
WORKDIR /app

# Install tools and create non-root user for security
RUN yum install -y shadow-utils && \
    groupadd -g 1000 joatse && \
    useradd -r -u 1000 -g joatse joatse && \
    mkdir -p /app/data && \
    chown -R joatse:joatse /app && \
    yum clean all

# Copy JAR from builder stage
COPY --from=builder /app/target/joatse-target-*.jar /app/joatse-target.jar

# Change ownership and switch to non-root user
RUN chown joatse:joatse /app/joatse-target.jar
USER joatse

# Set JVM options for container
ENV JAVA_OPTS="-Xmx256m -Xms128m -Djava.security.egd=file:/dev/./urandom"

# Configuration environment variables
ENV JOATSE_CLOUD_URL="ws://joatse-cloud:9011"
ENV JOATSE_TARGET_ID=""
ENV JOATSE_TARGET_PASSWORD=""

# Run the application
ENTRYPOINT ["java", "-jar", "/app/joatse-target.jar"]