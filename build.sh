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
    echo "用法: ./build.sh [选项]"
    echo ""
    echo "选项:"
    echo "  all       构建所有平台 (默认)"
    echo "  mac       仅构建 Mac 应用"
    echo "  android   仅构建 Android 应用"
    echo "  clean     清理构建产物"
    echo "  help      显示帮助信息"
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
    print_step "开始构建 Android 应用..."
    
    cd "$SCRIPT_DIR/VoiceSyncAndroid"
    
    # 创建输出目录
    mkdir -p "$OUTPUT_DIR"
    
    # 检查 gradlew 是否可执行
    if [ ! -x ./gradlew ]; then
        chmod +x ./gradlew
    fi
    
    # 构建 Debug APK (已签名，可直接安装)
    print_step "正在构建 APK..."
    ./gradlew assembleDebug --quiet
    
    # 复制到输出目录
    cp ./app/build/outputs/apk/debug/app-debug.apk "$OUTPUT_DIR/VoiceSync-Android.apk"
    
    # 获取 APK 大小
    APK_SIZE=$(du -sh "$OUTPUT_DIR/VoiceSync-Android.apk" | cut -f1)
    
    print_success "Android 应用构建完成: $OUTPUT_DIR/VoiceSync-Android.apk ($APK_SIZE)"
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
    build_android
    
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
        build_android
        ;;
    clean)
        clean_build
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        print_error "未知选项: $1"
        show_help
        exit 1
        ;;
esac
