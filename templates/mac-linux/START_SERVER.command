#!/usr/bin/env bash
cd "$(dirname "$0")"

while true; do
    # Apply pending orchestrator update if retrieved from cluster cloud
    if [ -f "ServerLauncher.jar.update" ]; then
        echo "[UPDATE] Applying new ServerLauncher update..."
        mv -f "ServerLauncher.jar.update" "ServerLauncher.jar"
    fi

    echo "========================================================"
    echo " Starting Minecraft Cluster Server..."
    echo "========================================================"

    # 1. Check for Java
    if ! command -v java >/dev/null 2>&1; then
        echo "[ERROR] Java 21 not found!"
        echo "Please install Java from https://adoptium.net/temurin/releases/?version=21"
        read -r -p "Press Enter to exit..."
        exit 1
    fi

    # 2. Check for rclone binary in bin/ or PATH, auto-download if missing
    if [ ! -f "bin/rclone" ] && ! command -v rclone >/dev/null 2>&1; then
        echo "[INFO] rclone not found. Downloading macOS rclone binary..."
        mkdir -p "bin"
        ARCH=$(uname -m)
        if [ "$ARCH" = "arm64" ]; then
            RCLONE_URL="https://downloads.rclone.org/rclone-current-osx-arm64.zip"
        else
            RCLONE_URL="https://downloads.rclone.org/rclone-current-osx-amd64.zip"
        fi
        TMP_ZIP=$(mktemp /tmp/rclone_XXXXXX.zip 2>/dev/null || echo "/tmp/rclone.zip")
        if curl -fsSL "$RCLONE_URL" -o "$TMP_ZIP"; then
            unzip -q -j "$TMP_ZIP" "*/rclone" -d "bin/"
            chmod +x "bin/rclone"
            rm -f "$TMP_ZIP"
            echo "[OK] rclone installed to bin/rclone."
        else
            echo "[WARN] Could not auto-download rclone. Please install via: brew install rclone"
        fi
    fi

    # 3. Verify ServerLauncher.jar exists
    if [ ! -f "ServerLauncher.jar" ]; then
        echo "[ERROR] ServerLauncher.jar not found!"
        echo "Ensure this folder was extracted completely from cohost-mac.zip."
        read -r -p "Press Enter to exit..."
        exit 1
    fi

    # 4. Launch orchestrator
    chmod +x bin/rclone 2>/dev/null || true
    java -jar ServerLauncher.jar "$@"
    EXIT_CODE=$?

    if [ "$EXIT_CODE" -eq 100 ] || [ -f "ServerLauncher.jar.update" ]; then
        echo "[UPDATE] ServerLauncher update detected! Restarting..."
        continue
    fi
    break
done
