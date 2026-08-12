# Dev container for media-extractor (Spring Boot + Maven + Java 25)
# Use a valid base image and install the Java 25 toolchain required by the project.
FROM mcr.microsoft.com/devcontainers/base:ubuntu

# Install Java 25 and Maven for the vscode user.
USER vscode
RUN bash -lc 'curl -s https://get.sdkman.io | bash && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk install java 25.0.2-tem && sdk install maven 3.9.10'

ENV JAVA_HOME=/home/vscode/.sdkman/candidates/java/current
ENV PATH="$JAVA_HOME/bin:/home/vscode/.sdkman/candidates/maven/current/bin:$PATH"

# Set working directory aligned with devcontainer config
WORKDIR /workspace
