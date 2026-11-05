#!/usr/bin/env bash
cd "$(dirname "$0")/.."

echo "========================================================"
echo " MC Cluster - Admin Remote Server Stop"
echo "========================================================"
echo ""
echo "This command signals the currently active host to save"
echo "all progress, warn players, and gracefully shut down."
echo ""
read -r -p "Enter shutdown reason (default: Admin maintenance): " REASON
REASON=${REASON:-Admin maintenance}

echo ""
if [ -f "minecraft-server/ServerLauncher.jar" ]; then
    java -jar "minecraft-server/ServerLauncher.jar" remote-stop "$REASON"
elif [ -f "ServerLauncher.jar" ]; then
    java -jar "ServerLauncher.jar" remote-stop "$REASON"
else
    echo "[ERROR] ServerLauncher.jar not found! Run mac-linux/build.sh first."
    exit 1
fi

