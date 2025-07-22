# Use Eclipse Temurin JRE 21 (OpenJDK)
FROM eclipse-temurin:21-jre

# Set working directory
WORKDIR /minecraft

# Create minecraft user for security
RUN groupadd -r minecraft && useradd -r -g minecraft -m -d /minecraft minecraft

# Create necessary directories
RUN mkdir -p /minecraft/mods /minecraft/logs /minecraft/world

# Copy the Fabric server launcher (you'll need to download this)
# Download from: https://fabricmc.net/use/server/
COPY fabric-server-mc.*.jar /minecraft/fabric-server-launch.jar

# Copy your mod jar file
COPY build/libs/*.jar /minecraft/mods/

# Copy server configuration if it exists
COPY server.properties /minecraft/server.properties* 
COPY eula.txt /minecraft/eula.txt*

# Set proper ownership
RUN chown -R minecraft:minecraft /minecraft

# Switch to minecraft user
USER minecraft

# Expose Minecraft server port and API port
EXPOSE 25565 7070

# Set JVM options
ENV JAVA_OPTS="-Xmx2G -Xms1G -XX:+UseG1GC -XX:+UseStringDeduplication"

# Health check for the API server
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:7070/ || exit 1

# Start the Minecraft server
CMD java $JAVA_OPTS -jar fabric-server-launch.jar nogui