#!/bin/bash

# VoiceSync 构建脚本
# 用于打包 Mac 和 Android 应用

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/dist"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_step() {
    echo -e "${BLUE}==>${NC} $1"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

# 显示帮助
show_help() {
    echo "VoiceSync 构建脚本"
    echo ""
    echo "用法: ./build.sh [命令]"
    echo ""
    echo "构建命令:"
    echo "  all              构建所有平台应用 (默认)"
    echo "  mac              构建 Mac 应用"
    echo "  android          构建 Android 应用 (正式版)"
    echo "  android-dev      构建 Android 应用 (开发版)"
    echo ""
    echo "安装命令:"
    echo "  install-mac      安装 Mac 应用到 Applications 目录"
    echo "  install-android  安装 Android 应用到连接的设备"
    echo ""
    echo "其他命令:"
    echo "  clean            清理所有构建产物"
    echo "  help             显示此帮助信息"
    echo ""
}

# 清理构建产物
clean_build() {
    print_step "清理构建产物..."
    
    rm -rf "$OUTPUT_DIR"
    rm -rf "$SCRIPT_DIR/VoiceSyncMac/build"
    rm -rf "$SCRIPT_DIR/VoiceSyncAndroid/app/build"
    
    print_success "清理完成"
}

# 构建 Mac 应用
build_mac() {
    print_step "开始构建 Mac 应用..."
    
    cd "$SCRIPT_DIR/VoiceSyncMac"
    
    # 创建输出目录
    mkdir -p "$OUTPUT_DIR"
    
    # 生成构建时间文件
    BUILD_TIME=$(date '+%Y-%m-%d %H:%M:%S')
    cat > "$SCRIPT_DIR/VoiceSyncMac/VoiceSyncMac/BuildTime.swift" << EOF
// 自动生成，请勿手动修改
import Foundation

let BUILD_TIME = "$BUILD_TIME"
EOF
    print_success "生成构建时间: $BUILD_TIME"
    
    # Archive
    print_step "正在 Archive..."
    xcodebuild -scheme VoiceSyncMac \
        -configuration Release \
        -archivePath ./build/VoiceSyncMac.xcarchive \
        archive \
        -quiet
    
    # 创建导出配置
    EXPORT_PLIST="./build/export_options.plist"
    cat > "$EXPORT_PLIST" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>mac-application</string>
</dict>
</plist>
EOF
    
    # 导出应用
    print_step "正在导出应用..."
    xcodebuild -exportArchive \
        -archivePath ./build/VoiceSyncMac.xcarchive \
        -exportPath ./build/export \
        -exportOptionsPlist "$EXPORT_PLIST" \
        -quiet
    
    # 复制到输出目录
    cp -r ./build/export/VoiceSyncMac.app "$OUTPUT_DIR/"
    
    # 获取应用大小
    APP_SIZE=$(du -sh "$OUTPUT_DIR/VoiceSyncMac.app" | cut -f1)
    
    print_success "Mac 应用构建完成: $OUTPUT_DIR/VoiceSyncMac.app ($APP_SIZE)"
}

# 构建 Android 应用
build_android() {
    local BUILD_TYPE="${1:-release}"
    
    if [ "$BUILD_TYPE" = "debug" ]; then
        print_step "开始构建 Android 应用 (Dev 版本)..."
        local GRADLE_TASK="assembleDebug"
        local OUTPUT_APK="app-debug.apk"
        local FINAL_NAME="VoiceSync-Android-Dev.apk"
    else
        print_step "开始构建 Android 应用 (Release 版本)..."
        local GRADLE_TASK="assembleRelease"
        local OUTPUT_APK="app-release.apk"
        local FINAL_NAME="VoiceSync-Android.apk"
    fi
    
    cd "$SCRIPT_DIR/VoiceSyncAndroid"
    
    # 创建输出目录
    mkdir -p "$OUTPUT_DIR"
    
    # 生成构建时间
    BUILD_TIME=$(date '+%Y-%m-%d %H:%M:%S')
    print_success "生成构建时间: $BUILD_TIME"
    
    # 检查 gradlew 是否可执行
    if [ ! -x ./gradlew ]; then
        chmod +x ./gradlew
    fi
    
    # 构建 APK
    print_step "正在构建 APK..."
    ./gradlew $GRADLE_TASK -PBUILD_TIME="$BUILD_TIME" --quiet
    
    # 复制到输出目录
    if [ "$BUILD_TYPE" = "debug" ]; then
        cp ./app/build/outputs/apk/debug/$OUTPUT_APK "$OUTPUT_DIR/$FINAL_NAME"
    else
        cp ./app/build/outputs/apk/release/$OUTPUT_APK "$OUTPUT_DIR/$FINAL_NAME"
    fi
    
    # 获取 APK 大小
    APK_SIZE=$(du -sh "$OUTPUT_DIR/VoiceSync-Android.apk" | cut -f1)
    
    print_success "Android 应用构建完成: $OUTPUT_DIR/VoiceSync-Android.apk ($APK_SIZE)"
}

# 查找 ADB
find_adb() {
    # 尝试多种方式查找 adb
    local adb_path=""
    
    # 1. 检查是否在 PATH 中
    if command -v adb &> /dev/null; then
        adb_path="adb"
    # 2. 检查 Android SDK 默认位置
    elif [ -f "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        adb_path="$HOME/Library/Android/sdk/platform-tools/adb"
    # 3. 检查常见的 Android Studio 位置
    elif [ -f "/Users/$USER/Library/Android/sdk/platform-tools/adb" ]; then
        adb_path="/Users/$USER/Library/Android/sdk/platform-tools/adb"
    fi
    
    echo "$adb_path"
}

# 安装 Android APK 到设备
install_android() {
    print_step "准备安装 Android APK..."
    
    # 检查 APK 是否存在
    APK_FILE="$OUTPUT_DIR/VoiceSync-Android.apk"
    if [ ! -f "$APK_FILE" ]; then
        print_error "APK 文件不存在: $APK_FILE"
        print_warning "请先运行: ./build.sh android"
        exit 1
    fi
    
    # 查找 adb
    ADB=$(find_adb)
    if [ -z "$ADB" ]; then
        print_error "未找到 adb 工具"
        print_warning "请确保已安装 Android SDK 或 Android Studio"
        exit 1
    fi
    
    print_success "找到 adb: $ADB"
    
    # 检查设备连接
    print_step "检查连接的设备..."
    DEVICES=$($ADB devices | grep -v "List of devices" | grep "device$" | wc -l)
    
    if [ "$DEVICES" -eq 0 ]; then
        print_error "没有检测到连接的 Android 设备"
        print_warning "请确保:"
        echo "  1. 手机已通过 USB 连接或无线调试连接"
        echo "  2. 已启用开发者选项和 USB 调试"
        exit 1
    fi
    
    print_success "检测到 $DEVICES 个设备"
    
    # 显示连接的设备
    echo ""
    $ADB devices
    echo ""
    
    # 安装 APK
    print_step "正在安装 APK..."
    if $ADB install -r "$APK_FILE"; then
        echo ""
        print_success "安装成功！应用已安装到你的 Android 设备"
        echo ""
        print_warning "提示: 如果应用已在运行，请重启应用以加载新版本"
    else
        print_error "安装失败"
        exit 1
    fi
}

# 安装 Mac 应用到 Applications 目录
install_mac() {
    print_step "准备安装 Mac 应用..."
    
    # 检查应用是否存在
    APP_FILE="$OUTPUT_DIR/VoiceSyncMac.app"
    if [ ! -d "$APP_FILE" ]; then
        print_error "应用文件不存在: $APP_FILE"
        print_warning "请先运行: ./build.sh mac"
        exit 1
    fi
    
    TARGET_PATH="/Applications/VoiceSyncMac.app"
    
    # 检查是否已安装旧版本
    if [ -d "$TARGET_PATH" ]; then
        print_warning "检测到已安装的版本，将进行替换..."
        
        # 如果应用正在运行，先关闭
        if pgrep -x "VoiceSyncMac" > /dev/null; then
            print_step "关闭正在运行的应用..."
            osascript -e 'quit app "VoiceSyncMac"' 2>/dev/null || true
            sleep 1
        fi
        
        # 删除旧版本
        print_step "删除旧版本..."
        rm -rf "$TARGET_PATH"
    fi
    
    # 复制新版本到 Applications
    print_step "正在安装应用到 Applications..."
    cp -r "$APP_FILE" "$TARGET_PATH"
    
    if [ -d "$TARGET_PATH" ]; then
        echo ""
        print_success "安装成功！应用已安装到 /Applications"
        echo ""
        print_warning "提示: 你现在可以从启动台或 Applications 文件夹打开 VoiceSyncMac"
        echo ""
        
        # 询问是否立即打开
        read -p "是否立即打开应用？[Y/n] " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]] || [[ -z $REPLY ]]; then
            open "$TARGET_PATH"
            print_success "应用已启动"
        fi
    else
        print_error "安装失败"
        exit 1
    fi
}

# 构建所有平台
build_all() {
    echo ""
    echo "╔════════════════════════════════════╗"
    echo "║     VoiceSync 构建脚本             ║"
    echo "╚════════════════════════════════════╝"
    echo ""
    
    build_mac
    echo ""
    build_android release
    
    echo ""
    echo "════════════════════════════════════"
    print_success "所有构建完成！"
    echo ""
    echo "输出目录: $OUTPUT_DIR"
    echo ""
    ls -lh "$OUTPUT_DIR"
    echo ""
}

# 主逻辑
case "${1:-all}" in
    all)
        build_all
        ;;
    mac)
        build_mac
        ;;
    android)
        build_android release
        ;;
    android-dev)
        build_android debug
        ;;
    install-android)
        install_android
        ;;
    install-mac)
        install_mac
        ;;
    clean)
        clean_build
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        print_error "未知命令: $1"
        echo ""
        show_help
        exit 1
        ;;
esac
        exit 1
        ;;
esac
