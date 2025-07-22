#!/bin/bash

# Download Fabric Server Launcher and Fabric API for Minecraft 1.21.7
# This script downloads the required files for the Docker build

FABRIC_SERVER_URL="https://meta.fabricmc.net/v2/versions/loader/1.21.7/0.16.14/1.0.3/server/jar"
FABRIC_API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/VjIVvVhW/fabric-api-0.128.1%2B1.21.7.jar"

FABRIC_SERVER_FILE="fabric-server-launch.jar"
FABRIC_API_FILE="fabric-api-0.128.1+1.21.7.jar"

# Create mods directory if it doesn't exist
mkdir -p mods

download_file() {
    local url="$1"
    local output="$2"
    
    if command -v curl &> /dev/null; then
        curl -L -o "$output" "$url"
    elif command -v wget &> /dev/null; then
        wget -O "$output" "$url"
    else
        echo "Error: Neither curl nor wget is available. Please install one of them."
        return 1
    fi
    return $?
}

echo "Downloading Fabric server launcher..."
echo "URL: $FABRIC_SERVER_URL"
echo "Output: $FABRIC_SERVER_FILE"

if download_file "$FABRIC_SERVER_URL" "$FABRIC_SERVER_FILE"; then
    echo "✅ Fabric server launcher downloaded successfully!"
    echo "File size: $(ls -lh $FABRIC_SERVER_FILE | awk '{print $5}')"
else
    echo "❌ Failed to download Fabric server launcher"
    exit 1
fi

echo ""
echo "Downloading Fabric API..."
echo "URL: $FABRIC_API_URL"
echo "Output: mods/$FABRIC_API_FILE"

if download_file "$FABRIC_API_URL" "mods/$FABRIC_API_FILE"; then
    echo "✅ Fabric API downloaded successfully!"
    echo "File size: $(ls -lh mods/$FABRIC_API_FILE | awk '{print $5}')"
else
    echo "❌ Failed to download Fabric API"
    exit 1
fi

echo ""
echo "✅ All downloads completed successfully!"
echo ""
echo "Files downloaded:"
echo "  - $FABRIC_SERVER_FILE (Fabric Server Launcher)"
echo "  - mods/$FABRIC_API_FILE (Fabric API)"
echo ""
echo "You can now build the Docker image with:"
echo "  ./gradlew dockerBuild"