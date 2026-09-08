#!/bin/bash

# Magic Window Adapter Build Script
# 魔窗适配模块构建脚本

set -e

echo "========================================"
echo "Magic Window Adapter Build Script"
echo "========================================"
echo ""

# 检查 Java 版本
echo "Checking Java version..."
java -version 2>&1 | head -n 1

# 检查 Gradle Wrapper
if [ ! -f "./gradlew" ]; then
    echo "❌ gradlew not found!"
    exit 1
fi

chmod +x ./gradlew

# 清理并构建
echo ""
echo "Building Release version..."
./gradlew clean assembleRelease

# 输出构建结果
echo ""
echo "========================================"
echo "Build completed!"
echo "========================================"
echo ""
echo "APK location:"
find ./app/build/outputs/apk -name "*.apk" -type f

echo ""
echo "✅ Done!"
