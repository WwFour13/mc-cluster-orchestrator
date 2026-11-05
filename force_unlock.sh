#!/usr/bin/env bash
cd "$(dirname "$0")"

echo "[Emergency Unlock] Attempting cloud and local unlock..."
if [ -f "minecraft-server/ServerLauncher.jar" ]; then
    java -jar "minecraft-server/ServerLauncher.jar" unlock
elif [ -f "ServerLauncher.jar" ]; then
    java -jar "ServerLauncher.jar" unlock
else
    echo "[INFO] Falling back to deleting local lock files..."
    rm -f "minecraft-server/.server_active.lock" ".server_active.lock"
    echo "[OK] Local locks cleared."
fi
