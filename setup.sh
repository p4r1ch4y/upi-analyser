#!/bin/bash
# Initial setup script for SpendLens development

set -e

echo "=== SpendLens Setup ==="
echo

# Check for required tools
check_command() {
    if ! command -v $1 &> /dev/null; then
        echo "❌ $1 not found. Please install it first."
        exit 1
    else
        echo "✅ $1 found"
    fi
}

echo "Checking prerequisites..."
check_command java
check_command adb

# Create font directory
echo
echo "Creating font directory..."
mkdir -p app/src/main/res/font
echo "✅ Font directory created: app/src/main/res/font/"

# Create mipmap directories for icons
echo
echo "Creating icon directories..."
for dpi in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    mkdir -p app/src/main/res/mipmap-$dpi
done
echo "✅ Icon directories created"

# Download Gradle wrapper if needed
if [ ! -f "gradlew" ]; then
    echo
    echo "Downloading Gradle wrapper..."
    gradle wrapper --gradle-version 8.5
fi

# Make gradlew executable
chmod +x gradlew

echo
echo "=== Setup Instructions ==="
echo
echo "1. Download fonts and place in app/src/main/res/font/:"
echo "   - Bricolage Grotesque SemiBold"
echo "   - IBM Plex Sans (Regular, Medium, SemiBold)"
echo
echo "2. Generate launcher icons using Android Studio:"
echo "   Right-click app/res → New → Image Asset"
echo
echo "3. Sync Gradle:"
echo "   ./gradlew build"
echo
echo "4. Run tests:"
echo "   ./gradlew test"
echo
echo "5. Install on device:"
echo "   ./gradlew installStandardDebug"
echo
echo "See BUILD.md for detailed instructions."
echo
