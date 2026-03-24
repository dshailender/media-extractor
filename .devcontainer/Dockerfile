# Dev container for media-extractor (Spring Boot + Maven + Java 21)
# Based on official VS Code devcontainer images.
FROM mcr.microsoft.com/vscode/devcontainers/java:0-21

# Ensure Maven and common tooling are available.
RUN apt-get update \
  && apt-get install -y --no-install-recommends maven git curl unzip \
  && rm -rf /var/lib/apt/lists/*

# Set working directory aligned with devcontainer config
WORKDIR /workspace
