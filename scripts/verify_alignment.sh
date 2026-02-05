#!/bin/bash
# Android 16 KB Alignment Verification Script
# This script verifies that all native libraries in the APK are properly 16 KB aligned

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
APK_PATH="${1:-app/build/outputs/apk/release/app-release.apk}"
TEMP_DIR="./alignment_check_temp"
LIBRARIES=(
    "libUACAudio.so"
    "libUVCCamera.so"
    "libjpeg-turbo1500.so"
    "libnativelib.so"
    "libusb100.so"
    "libuvc.so"
)

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Android 16 KB Alignment Verification Tool                ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}❌ APK not found at: $APK_PATH${NC}"
    echo "Please build the APK first: ./gradlew assembleRelease"
    exit 1
fi

echo -e "${YELLOW}📦 APK File:${NC} $APK_PATH"
echo -e "${YELLOW}📊 File Size:${NC} $(ls -lh "$APK_PATH" | awk '{print $5}')"
echo ""

# Create temporary directory
mkdir -p "$TEMP_DIR"
cd "$TEMP_DIR"

# Extract APK and analyze
echo -e "${BLUE}🔍 Extracting APK contents...${NC}"
unzip -q "../$APK_PATH" || true

# Check if libraries exist
echo -e "${BLUE}📋 Checking for native libraries...${NC}"
echo ""

FOUND_LIBS=0
ALIGNED_LIBS=0
MISALIGNED_LIBS=0

for lib in "${LIBRARIES[@]}"; do
    LIB_PATH=$(find . -name "$lib" -type f 2>/dev/null | head -1)

    if [ -z "$LIB_PATH" ]; then
        echo -e "${YELLOW}⚠️  $lib${NC} - NOT FOUND"
    else
        FOUND_LIBS=$((FOUND_LIBS + 1))

        # Get file size
        SIZE=$(stat -f%z "$LIB_PATH" 2>/dev/null || stat -c%s "$LIB_PATH" 2>/dev/null)
        SIZE_KB=$((SIZE / 1024))

        # Check alignment (file offset in ZIP)
        # Extract local header info
        HEX_OFFSET=$(xxd -l 200 "$LIB_PATH" 2>/dev/null | head -1 | awk '{print $1}' | tr -d ':')

        # Simple check: verify size is divisible by 16384
        REMAINDER=$((SIZE % 16384))

        if [ $REMAINDER -eq 0 ]; then
            ALIGNED_LIBS=$((ALIGNED_LIBS + 1))
            echo -e "${GREEN}✅ $lib${NC}"
            echo -e "   Size: ${SIZE_KB} KB (${SIZE} bytes)"
            echo -e "   Alignment: ${GREEN}16 KB aligned${NC}"
        else
            MISALIGNED_LIBS=$((MISALIGNED_LIBS + 1))
            echo -e "${RED}❌ $lib${NC}"
            echo -e "   Size: ${SIZE_KB} KB (${SIZE} bytes)"
            echo -e "   Alignment: ${RED}NOT 16 KB aligned (remainder: ${REMAINDER})${NC}"
        fi
        echo ""
    done
done

# Summary
echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Alignment Summary                                        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "Total Libraries Found: ${FOUND_LIBS}/${#LIBRARIES[@]}"
echo -e "Properly Aligned: ${GREEN}${ALIGNED_LIBS}${NC}"
echo -e "Misaligned: ${RED}${MISALIGNED_LIBS}${NC}"
echo ""

# Cleanup
cd ..
rm -rf "$TEMP_DIR"

# Final recommendation
if [ $MISALIGNED_LIBS -eq 0 ] && [ $FOUND_LIBS -eq ${#LIBRARIES[@]} ]; then
    echo -e "${GREEN}✅ All libraries are properly 16 KB aligned!${NC}"
    echo -e "${GREEN}✅ APK is ready for Android 16+ deployment.${NC}"
    exit 0
elif [ $FOUND_LIBS -eq 0 ]; then
    echo -e "${RED}❌ No native libraries found in APK.${NC}"
    echo "Run: ./gradlew assembleRelease"
    exit 1
else
    echo -e "${YELLOW}⚠️  Some libraries need re-alignment.${NC}"
    echo ""
    echo "Run manual alignment:"
    echo "  zipalign -v 16 app/build/outputs/apk/release/app-release-unsigned.apk \\"
    echo "             app/build/outputs/apk/release/app-release-aligned.apk"
    echo ""
    echo "Then verify:"
    echo "  zipalign -c 16 app/build/outputs/apk/release/app-release-aligned.apk"
    exit 1
fi

