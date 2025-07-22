#!/bin/bash

# Alternative Fabric API download using Modrinth API
# This script uses the Modrinth API to get the correct download URL

FABRIC_API_VERSION="0.128.1+1.21.7"
MODRINTH_PROJECT_ID="P7dR8mSH"  # Fabric API project ID
OUTPUT_FILE="mods/fabric-api-0.128.1+1.21.7.jar"

# Create mods directory
mkdir -p mods

echo "Getting download URL from Modrinth API for Fabric API $FABRIC_API_VERSION..."

# Get version info from Modrinth API
VERSION_INFO=$(curl -s "https://api.modrinth.com/v2/project/$MODRINTH_PROJECT_ID/version/$FABRIC_API_VERSION")

if [ $? -ne 0 ] || [ -z "$VERSION_INFO" ]; then
    echo "❌ Failed to get version info from Modrinth API"
    exit 1
fi

# Extract download URL using basic tools (grep and sed)
DOWNLOAD_URL=$(echo "$VERSION_INFO" | grep -o '"url":"[^"]*' | head -1 | sed 's/"url":"//')

if [ -z "$DOWNLOAD_URL" ]; then
    echo "❌ Could not extract download URL from API response"
    echo "API Response: $VERSION_INFO"
    exit 1
fi

echo "Download URL: $DOWNLOAD_URL"
echo "Output: $OUTPUT_FILE"

# Download the file
if command -v curl &> /dev/null; then
    curl -L -o "$OUTPUT_FILE" "$DOWNLOAD_URL"
elif command -v wget &> /dev/null; then
    wget -O "$OUTPUT_FILE" "$DOWNLOAD_URL"
else
    echo "❌ Neither curl nor wget is available"
    exit 1
fi

# Validate the download
if [ -f "$OUTPUT_FILE" ] && [ -s "$OUTPUT_FILE" ]; then
    echo "✅ Fabric API downloaded successfully!"
    echo "File size: $(ls -lh $OUTPUT_FILE | awk '{print $5}')"
    
    # Test if it's a valid ZIP/JAR file
    if command -v unzip &> /dev/null; then
        if unzip -t "$OUTPUT_FILE" &> /dev/null; then
            echo "✅ JAR file validated successfully"
        else
            echo "❌ Downloaded JAR file appears to be corrupted"
            exit 1
        fi
    fi
else
    echo "❌ Download failed or file is empty"
    exit 1
fi