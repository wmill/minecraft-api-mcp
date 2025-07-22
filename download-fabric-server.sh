#!/bin/bash

# Download Fabric Server Launcher for Minecraft 1.21
# This script downloads the Fabric server launcher required for the Docker build

FABRIC_URL="https://meta.fabricmc.net/v2/versions/loader/1.21.8/0.16.14/1.0.3/server/jar"
OUTPUT_FILE="fabric-server-launch.jar"

echo "Downloading Fabric server launcher..."
echo "URL: $FABRIC_URL"
echo "Output: $OUTPUT_FILE"

if command -v curl &> /dev/null; then
    curl -L -o "$OUTPUT_FILE" "$FABRIC_URL"
elif command -v wget &> /dev/null; then
    wget -O "$OUTPUT_FILE" "$FABRIC_URL"
else
    echo "Error: Neither curl nor wget is available. Please install one of them."
    exit 1
fi

if [ -f "$OUTPUT_FILE" ]; then
    echo "✅ Fabric server launcher downloaded successfully!"
    echo "File size: $(ls -lh $OUTPUT_FILE | awk '{print $5}')"
    echo ""
    echo "You can now build the Docker image with:"
    echo "  ./gradlew dockerBuild"
else
    echo "❌ Failed to download Fabric server launcher"
    exit 1
fi