#!/usr/bin/env bash
# Minecraft Cluster Host Launch Script for Linux & macOS
cd "$(dirname "$0")"

# 1. Check if Syncthing daemon is running on port 8384
if ! (curl -s --connect-timeout 2 http://127.0.0.1:8384 >/dev/null 2>&1); then
    echo "[INFO] Syncthing is not listening on port 8384. Starting daemon in background..."
    if command -v syncthing >/dev/null 2>&1; then
        syncthing --no-browser &
    elif [ -x "/Applications/Syncthing.app/Contents/MacOS/Syncthing" ]; then
        /Applications/Syncthing.app/Contents/MacOS/Syncthing --no-browser &
    else
        echo "[WARN] Syncthing binary not found on PATH or standard application directories."
    fi
    sleep 3
fi

# 2. Verify orchestrator binary exists
if [ ! -f "ServerLauncher.jar" ]; then
    echo "[ERROR] ServerLauncher.jar not found!"
    echo "Please build ServerLauncher.jar before launching."
    exit 1
fi

# 3. Launch cluster orchestrator
java -jar ServerLauncher.jar

