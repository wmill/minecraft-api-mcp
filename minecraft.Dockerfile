# Use Eclipse Temurin JRE 21 (OpenJDK)
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl gosu tmux && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /minecraft

# Create minecraft user for security
RUN groupadd -r minecraft && useradd -r -g minecraft -m -d /minecraft minecraft

# Create image-owned distribution directory and runtime directories
RUN mkdir -p /opt/minecraft-dist/mods /minecraft/logs /minecraft/world

# Copy the Fabric server launcher from mods directory
COPY mods/fabric-server-launch.jar /opt/minecraft-dist/fabric-server-launch.jar

# Copy your mod jar file (remapped only)
# ARG JAR_FILE
# RUN test -n "${JAR_FILE}" || (echo "JAR_FILE build arg is required (e.g., modid-1.0.0-fat-remapped.jar)" && exit 1)
# COPY build/libs/${JAR_FILE} /opt/minecraft-dist/mods/
COPY build/libs/modid-*-remapped.jar /opt/minecraft-dist/mods/

# Copy Fabric API and other mods from mods directory (excluding server launcher)
COPY mods/fabric-api-*.jar /opt/minecraft-dist/mods/

# Copy server configuration files
COPY server.properties eula.txt /opt/minecraft-dist/
COPY minecraft-entrypoint.sh /usr/local/bin/minecraft-entrypoint.sh

# Set proper ownership
RUN chown -R minecraft:minecraft /minecraft /opt/minecraft-dist \
    && chmod +x /usr/local/bin/minecraft-entrypoint.sh

# Expose Minecraft server port and API port
EXPOSE 25565 7070

# Set JVM options
ENV JAVA_OPTS="-Xmx2G -Xms1G -XX:+UseG1GC -XX:+UseStringDeduplication"

# Health check for the API server
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:7070/ || exit 1

ENTRYPOINT ["/usr/local/bin/minecraft-entrypoint.sh"]

# Start the Minecraft server
CMD ["tmux", "new-session", "-s", "minecraft", "java", "-Xmx2G", "-Xms1G", "-jar", "fabric-server-launch.jar", "nogui"]
