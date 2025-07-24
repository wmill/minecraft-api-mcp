#!/bin/bash

# Download Fabric Server Launcher and Fabric API for Minecraft 1.21.7
# This script downloads the required files for the Docker build

FABRIC_SERVER_URL="https://meta.fabricmc.net/v2/versions/loader/1.21.7/0.16.14/1.0.3/server/jar"
# Use GitHub releases for more reliable downloads
FABRIC_API_URL="https://github.com/FabricMC/fabric/releases/download/0.128.1%2B1.21.7/fabric-api-0.128.1%2B1.21.7.jar"

FABRIC_SERVER_FILE="mods/fabric-server-launch.jar"
FABRIC_API_FILE="fabric-api-0.128.1+1.21.7.jar"

# Create mods directory if it doesn't exist
mkdir -p mods

download_file() {
    local url="$1"
    local output="$2"
    local retries=3
    
    for i in $(seq 1 $retries); do
        echo "  Attempt $i/$retries..."
        
        if command -v curl &> /dev/null; then
            curl -L -f --retry 2 --retry-delay 2 -o "$output" "$url"
        elif command -v wget &> /dev/null; then
            wget --tries=2 --timeout=30 -O "$output" "$url"
        else
            echo "Error: Neither curl nor wget is available. Please install one of them."
            return 1
        fi
        
        # Check if download was successful and file is not empty
        if [ $? -eq 0 ] && [ -f "$output" ] && [ -s "$output" ]; then
            # Additional validation for JAR files
            if [[ "$output" == *.jar ]]; then
                if command -v unzip &> /dev/null; then
                    if unzip -t "$output" &> /dev/null; then
                        echo "  ✅ File downloaded and validated successfully"
                        return 0
                    else
                        echo "  ❌ Downloaded JAR file is corrupted, retrying..."
                        rm -f "$output"
                    fi
                else
                    # If unzip is not available, just check file size
                    local size=$(stat -c%s "$output" 2>/dev/null || stat -f%z "$output" 2>/dev/null || echo "0")
                    if [ "$size" -gt 1000 ]; then
                        echo "  ✅ File downloaded successfully (size: $size bytes)"
                        return 0
                    else
                        echo "  ❌ Downloaded file too small, retrying..."
                        rm -f "$output"
                    fi
                fi
            else
                echo "  ✅ File downloaded successfully"
                return 0
            fi
        else
            echo "  ❌ Download failed, retrying..."
            rm -f "$output"
        fi
        
        if [ $i -lt $retries ]; then
            echo "  Waiting 2 seconds before retry..."
            sleep 2
        fi
    done
    
    echo "  ❌ Failed to download after $retries attempts"
    return 1
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