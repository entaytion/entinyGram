#!/bin/bash

# Build script for entinyGram with VPN proxy fix
# Usage: ./build-vpn-fix.sh [debug|release]

BUILD_TYPE=${1:-debug}

echo "🔨 Building entinyGram APK - VPN Proxy Fix"
echo "=========================================="
echo "Build Type: $BUILD_TYPE"
echo ""

# Check if bun is installed
if ! command -v bun &> /dev/null; then
    echo "❌ Bun not found. Install from https://bun.sh"
    exit 1
fi

# Check if we're in the right directory
if [ ! -f "bun.lock" ]; then
    echo "❌ Not in entinyGram root directory"
    exit 1
fi

echo "📦 Installing dependencies..."
bun install

echo "🔧 Setting up workspace..."
bun run setup

echo "✓ Checking patches..."
bun run lint-patches

echo ""
echo "🏗️  Building APK ($BUILD_TYPE)..."

cd worktree || exit 1

if [ "$BUILD_TYPE" = "debug" ]; then
    ./gradlew TMessagesProj_App:assembleDebug --no-daemon
    if [ $? -eq 0 ]; then
        APK_PATH="TMessagesProj_App/build/outputs/apk/debug/app.apk"
        echo ""
        echo "✅ Debug APK built successfully!"
        echo "📱 Location: $APK_PATH"
        echo ""
        echo "📲 Install with:"
        echo "   adb install -r $APK_PATH"
    fi
elif [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew TMessagesProj_App:assembleRelease --no-daemon
    if [ $? -eq 0 ]; then
        APK_PATH="TMessagesProj_App/build/outputs/apk/release/app.apk"
        echo ""
        echo "✅ Release APK built successfully!"
        echo "📱 Location: $APK_PATH"
    fi
else
    echo "❌ Unknown build type: $BUILD_TYPE"
    echo "Usage: ./build-vpn-fix.sh [debug|release]"
    exit 1
fi

cd .. || exit 1
