# Inherit from your specialized Java 25 Base Class
FROM eclipse-temurin:25-jdk

# Install Python and Maven
RUN apt-get update && apt-get install -y \
    maven \
    python3 \
    python3-pip \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Set the Workspace pointer
WORKDIR /workspace

CMD ["/bin/bash"]