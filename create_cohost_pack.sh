#!/usr/bin/env bash
cd "$(dirname "$0")"

ORCH_DIR="$(pwd)"
SERVER_DIR="$ORCH_DIR/minecraft-server"
OUTPUT_ZIP="$ORCH_DIR/cohost-starter-pack.zip"

echo "========================================================"
echo "[Cohost Packager] Preparing pre-configured starter pack..."
echo "========================================================"

if [ ! -f "$SERVER_DIR/ServerLauncher.jar" ]; then
    echo "[ERROR] ServerLauncher.jar not found in $SERVER_DIR. Run ./build.sh first!"
    exit 1
fi
if [ ! -f "$SERVER_DIR/bin/core.dat" ]; then
    echo "[ERROR] core.dat not found in $SERVER_DIR/bin. Ensure your Paper jar is in bin/core.dat."
    exit 1
fi
if [ ! -f "$SERVER_DIR/config.json" ]; then
    echo "[ERROR] config.json not found in $SERVER_DIR. Configure your Backblaze keys first!"
    exit 1
fi

STAGING_DIR=$(mktemp -d 2>/dev/null || mktemp -d -t 'cohost_staging')
TARGET_SERVER="$STAGING_DIR/minecraft-server"
mkdir -p "$TARGET_SERVER/bin"

echo "[1/4] Staging runtime and launch scripts..."
FILES=(
    "Setup_Desktop_Shortcut.bat" "setup_shortcut.ps1" "Setup_Mac_App.sh"
    "run_server.bat" "run_server.sh"
    "sync_pull.bat" "sync_pull.sh"
    "sync_push.bat" "sync_push.sh"
    "rclone-filter.txt" "ServerLauncher.jar"
    "config.json" "eula.txt" "server.properties"
    "bukkit.yml" "spigot.yml" "commands.yml"
    "ops.json" "whitelist.json" "banned-ips.json" "banned-players.json"
)
for f in "${FILES[@]}"; do
    if [ -f "$SERVER_DIR/$f" ]; then
        cp "$SERVER_DIR/$f" "$TARGET_SERVER/"
    fi
done
chmod +x "$TARGET_SERVER"/*.sh 2>/dev/null || true

echo "[2/4] Staging binaries (bin/core.dat, bin/rclone, bin/icon.ico)..."
[ -f "$SERVER_DIR/bin/core.dat" ] && cp "$SERVER_DIR/bin/core.dat" "$TARGET_SERVER/bin/"
[ -f "$SERVER_DIR/bin/rclone.exe" ] && cp "$SERVER_DIR/bin/rclone.exe" "$TARGET_SERVER/bin/"
[ -f "$SERVER_DIR/bin/rclone" ] && cp "$SERVER_DIR/bin/rclone" "$TARGET_SERVER/bin/"
[ -f "$SERVER_DIR/bin/icon.ico" ] && cp "$SERVER_DIR/bin/icon.ico" "$TARGET_SERVER/bin/"

echo "[3/4] Staging plugins and configurations..."
[ -d "$SERVER_DIR/plugins" ] && cp -r "$SERVER_DIR/plugins" "$TARGET_SERVER/"
[ -d "$SERVER_DIR/config" ] && cp -r "$SERVER_DIR/config" "$TARGET_SERVER/"

echo "[4/4] Compressing starter package into zip..."
rm -f "$OUTPUT_ZIP"
(cd "$STAGING_DIR" && zip -r -q "$OUTPUT_ZIP" minecraft-server)

rm -rf "$STAGING_DIR"

if [ -f "$OUTPUT_ZIP" ]; then
    SIZE_MB=$(du -m "$OUTPUT_ZIP" | cut -f1)
    echo "========================================================"
    echo "[SUCCESS] Cohost starter pack created successfully!"
    echo "  Location : $OUTPUT_ZIP"
    echo "  Zip Size : ~${SIZE_MB} MB"
    echo "========================================================"
    echo "Send this zip to your co-hosts!"
    echo "All they have to do is:"
    echo "  1. Extract the zip."
    echo "  2. Run ./run_server.sh (or run_server.bat on Windows)."
    echo "The launcher will connect to Backblaze, pull the newest world, and start!"
else
    echo "[ERROR] Failed to compress starter package."
    exit 1
fi

