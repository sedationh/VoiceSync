#!/bin/bash

# release.sh - Upload VoiceSync Mac and Android apps to GitHub Releases

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

# --- 2. Prepare Release Files ---

# Check for 'dist' directory
if [ ! -d "dist" ]; then
    echo "❌ Error: 'dist' directory not found in current path."
    echo "   Please ensure you have built the project and the 'dist' folder exists."
    exit 1
fi

# --- Verify 'dist' contents ---
MISSING_ARTIFACTS=0
MAC_APP=""
ANDROID_APK=""

if [ ! -d "dist/VoiceSyncMac.app" ]; then
    echo "⚠️  Warning: 'dist/VoiceSyncMac.app' is missing."
    MISSING_ARTIFACTS=1
else
    MAC_APP="dist/VoiceSyncMac.app"
    echo "✅ Found Mac app: VoiceSyncMac.app"
fi

if [ ! -f "dist/VoiceSync-Android.apk" ]; then
    echo "⚠️  Warning: 'dist/VoiceSync-Android.apk' is missing."
    MISSING_ARTIFACTS=1
else
    ANDROID_APK="dist/VoiceSync-Android.apk"
    echo "✅ Found Android APK: VoiceSync-Android.apk"
fi

if [ $MISSING_ARTIFACTS -eq 1 ]; then
    echo ""
    read -p "⚠️  Some artifacts are missing. Do you want to proceed anyway? (y/N) " CONFIRM
    if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
        echo "❌ Operation cancelled. Please run ./build.sh to generate missing artifacts."
        exit 1
    fi
    echo "⚠️  Proceeding with available artifacts..."
fi

# Package Mac app as zip for easier download
if [ -n "$MAC_APP" ]; then
    MAC_ZIP="dist/VoiceSyncMac.zip"
    echo "📦 Packaging Mac app to VoiceSyncMac.zip..."
    
    # Remove old zip if exists
    rm -f "$MAC_ZIP"
    
    # Create zip from within dist directory to avoid nested paths
    (cd dist && zip -r -9 -q "../$MAC_ZIP" VoiceSyncMac.app -x "*/.DS_Store")
    
    if [ -f "$MAC_ZIP" ]; then
        SIZE=$(ls -lh "$MAC_ZIP" | awk '{print $5}')
        echo "✅ Created VoiceSyncMac.zip ($SIZE)"
    else
        echo "❌ Error: Failed to create Mac zip file."
        exit 1
    fi
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
    read -p "   Do you want to upload/overwrite files to this release? (y/N) " CONFIRM
    if [[ "$CONFIRM" =~ ^[Yy]$ ]]; then
        echo "⬆️  Uploading files to existing release '$TAG_NAME'..."
        [ -n "$MAC_APP" ] && [ -f "$MAC_ZIP" ] && gh release upload "$TAG_NAME" "$MAC_ZIP" --clobber
        [ -n "$ANDROID_APK" ] && gh release upload "$TAG_NAME" "$ANDROID_APK" --clobber
        echo "✅ Upload complete."
    else
        echo "   Operation cancelled."
        exit 0
    fi
else
    echo "🚀 Creating new release '$TAG_NAME'..."
    
    # Create release notes
    RELEASE_NOTES="# 🎙️ VoiceSync $TAG_NAME

## 📥 下载说明

### macOS 用户
下载 **VoiceSyncMac.zip**，解压后将 \`VoiceSyncMac.app\` 拖入应用程序文件夹。

首次运行需要授予以下权限：
- ✅ 网络连接权限（必须）- 接收手机数据
- ✅ 辅助功能权限（可选）- 自动粘贴功能

### Android 用户
下载 **VoiceSync-Android.apk**，直接安装即可。

需要手机和 Mac 连接在同一 Wi-Fi 网络下。

---

## ✨ 主要功能

- 🎤 手机语音输入，文字自动同步到电脑
- ⚡ 智能自动发送（停止说话 2 秒后自动发送）
- 🧹 自动清除内容（发送成功后 3 秒自动清空）
- ⌨️ 自动粘贴（可选自动执行 Cmd+V）
- 🔄 同步历史记录
- 📱 IP 地址历史管理

---

## 🚀 快速开始

1. **Mac 端**：打开应用，查看显示的本机 IP 地址
2. **Android 端**：打开应用，设置中输入 Mac 的 IP 地址
3. **开始使用**：在手机上说话，文字自动出现在电脑上

详细使用说明请查看 [README](https://github.com/sedaoturak/VoiceSync/blob/main/README.md)

---

## 📊 效率提升

使用语音识别相比传统打字，效率提升超过 **10 倍**，每天可节省 **30-40 分钟**。
"
    
    # Create release with detailed notes
    echo "$RELEASE_NOTES" > .release_notes.tmp
    
    # Upload files
    UPLOAD_FILES=()
    [ -n "$MAC_APP" ] && [ -f "$MAC_ZIP" ] && UPLOAD_FILES+=("$MAC_ZIP")
    [ -n "$ANDROID_APK" ] && UPLOAD_FILES+=("$ANDROID_APK")
    
    if [ ${#UPLOAD_FILES[@]} -eq 0 ]; then
        echo "❌ Error: No files to upload."
        rm -f .release_notes.tmp
        exit 1
    fi
    
    gh release create "$TAG_NAME" "${UPLOAD_FILES[@]}" \
        --title "VoiceSync $TAG_NAME" \
        --notes-file .release_notes.tmp
    
    rm -f .release_notes.tmp
    
    echo "✅ Release '$TAG_NAME' published successfully!"
fi

# --- 4. Cleanup ---
echo ""
echo "🧹 Cleaning up..."
[ -f "$MAC_ZIP" ] && rm -f "$MAC_ZIP" && echo "✅ Removed temporary file VoiceSyncMac.zip"
