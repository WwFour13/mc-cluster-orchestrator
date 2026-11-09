#!/usr/bin/env bash
cd "$(dirname "$0")/.." || exit 1
ORCH_DIR="$(pwd)"

echo "========================================================"
echo "[MC Cluster Orchestrator] Building and Publishing Update"
echo "========================================================"

if [ -f "$ORCH_DIR/mac-linux/build.sh" ]; then
    echo "[1/2] Compiling latest orchestrator binary..."
    bash "$ORCH_DIR/mac-linux/build.sh" || exit 1
fi

cd "$ORCH_DIR/minecraft-server" || exit 1
echo ""
echo "[2/2] Publishing administrative update to Backblaze B2..."
java -jar ServerLauncher.jar publish-update
