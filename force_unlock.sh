#!/usr/bin/env bash
# Emergency Cluster Lock Release Tool for Linux & macOS
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FOUND=0

for lock in "$SCRIPT_DIR/minecraft-server/.server_active.lock" "$SCRIPT_DIR/.server_active.lock" "$SCRIPT_DIR/../minecraft-server/.server_active.lock"; do
    if [ -f "$lock" ]; then
        rm -f "$lock"
        echo "[OK] Emergency cluster lock released at: $lock"
        FOUND=1
    fi
done

if [ "$FOUND" -eq 0 ]; then
    echo "[INFO] No active cluster lock found (.server_active.lock)."
fi

