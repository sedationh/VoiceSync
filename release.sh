#!/bin/bash

# release.sh - Automate zipping 'dist' and uploading to GitHub Releases

set -e  # Exit immediately if a command exits with a non-zero status.

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# --- 1. Check Prerequisites ---

# Check for 'gh' (GitHub CLI)
if ! command_exists gh; then
    echo "❌ Error: GitHub CLI ('gh') is not installed."
    echo "   This script requires 'gh' to interact with GitHub."
    echo ""
    echo "   To install on macOS (using Homebrew):"
    echo "     brew install gh"
    echo ""
    echo "   After installing, please authenticate:"
    echo "     gh auth login"
    exit 1
fi

# Check authentication status
echo "Checking GitHub CLI authentication..."
if ! gh auth status >/dev/null 2>&1; then
    echo "❌ Error: You are not logged in to GitHub CLI."
    echo "   Please run the following command to login:"
    echo "     gh auth login"
    exit 1
fi

# --- 2. Prepare Archive ---

# Check for 'dist' directory
if [ ! -d "dist" ]; then
    echo "❌ Error: 'dist' directory not found in current path."
    echo "   Please ensure you have built the project and the 'dist' folder exists."
    exit 1
fi

# --- Verify 'dist' contents ---
MISSING_ARTIFACTS=0

if [ ! -d "dist/VoiceSyncMac.app" ]; then
    echo "⚠️  Warning: 'dist/VoiceSyncMac.app' is missing."
    MISSING_ARTIFACTS=1
fi

if [ ! -f "dist/VoiceSync-Android.apk" ]; then
    echo "⚠️  Warning: 'dist/VoiceSync-Android.apk' is missing."
    MISSING_ARTIFACTS=1
fi

if [ $MISSING_ARTIFACTS -eq 1 ]; then
    echo ""
    read -p "⚠️  Some artifacts are missing. Do you want to proceed with packaging anyway? (y/N) " CONFIRM
    if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
        echo "❌ Operation cancelled. Please run ./build.sh to generate missing artifacts."
        exit 1
    fi
    echo "⚠️  Proceeding with incomplete artifacts..."
else
    echo "✅ Verified 'dist' contents: VoiceSyncMac.app and VoiceSync-Android.apk found."
fi

ZIP_FILE="dist.zip"
echo "📦 Zipping 'dist' directory to $ZIP_FILE..."

# Remove old zip if exists to ensure a fresh build
rm -f "$ZIP_FILE"

# Create zip: 
# -r: recursive
# -9: best compression
# -q: quiet mode
# -x: exclude .DS_Store files
zip -r -9 -q "$ZIP_FILE" dist -x "*/.DS_Store"

if [ -f "$ZIP_FILE" ]; then
    SIZE=$(ls -lh "$ZIP_FILE" | awk '{print $5}')
    echo "✅ Created $ZIP_FILE ($SIZE)"
else
    echo "❌ Error: Failed to create zip file."
    exit 1
fi

# --- 3. Release to GitHub ---

echo ""
echo "Ready to upload to GitHub."

# Function to increment version
increment_version() {
    local ver=$1
    # Remove 'v' prefix if present
    ver="${ver#v}"
    
    # Split into array based on . delimiter
    IFS='.' read -r -a parts <<< "$ver"
    
    # If we don't have 3 parts (x.y.z), default to 0.0.1
    if [ "${#parts[@]}" -lt 3 ]; then
        echo "0.0.1"
        return
    fi
    
    # Increment the last part (patch version)
    local major="${parts[0]}"
    local minor="${parts[1]}"
    local patch="${parts[2]}"
    
    patch=$((patch + 1))
    
    echo "$major.$minor.$patch"
}

# Get latest release tag from GitHub
echo "🔍 Checking latest release version..."
LATEST_TAG=$(gh release list --limit 1 | awk '{print $1}')

if [ -z "$LATEST_TAG" ]; then
    NEXT_VERSION="v0.0.1"
    echo "   No existing releases found. Defaulting to $NEXT_VERSION"
else
    echo "   Latest version found: $LATEST_TAG"
    NEXT_VERSION="v$(increment_version "$LATEST_TAG")"
    echo "   Next version will be: $NEXT_VERSION"
fi

read -p "Enter release tag (default: $NEXT_VERSION): " TAG_NAME
TAG_NAME=${TAG_NAME:-$NEXT_VERSION}

if [ -z "$TAG_NAME" ]; then
    echo "❌ Error: Tag name cannot be empty."
    exit 1
fi

echo ""
# Check if release already exists
if gh release view "$TAG_NAME" >/dev/null 2>&1; then
    echo "⚠️  Release '$TAG_NAME' already exists."
    read -p "   Do you want to upload/overwrite $ZIP_FILE to this release? (y/N) " CONFIRM
    if [[ "$CONFIRM" =~ ^[Yy]$ ]]; then
        echo "⬆️  Uploading $ZIP_FILE to existing release '$TAG_NAME'..."
        gh release upload "$TAG_NAME" "$ZIP_FILE" --clobber
        echo "✅ Upload complete."
    else
        echo "   Operation cancelled."
        exit 0
    fi
else
    echo "🚀 Creating new release '$TAG_NAME' and uploading asset..."
    # Create release and upload asset
    # --generate-notes: Automatically generates release notes based on PRs/commits
    gh release create "$TAG_NAME" "$ZIP_FILE" --title "$TAG_NAME" --generate-notes
    
    echo "✅ Release '$TAG_NAME' published successfully!"
fi

# --- 4. Cleanup ---
echo ""
echo "🧹 Cleaning up..."
rm -f "$ZIP_FILE"
echo "✅ Removed temporary file $ZIP_FILE"
