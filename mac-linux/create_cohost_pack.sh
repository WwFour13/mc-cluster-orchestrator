#!/usr/bin/env bash
cd "$(dirname "$0")/.."

ORCH_DIR="$(pwd)"
SERVER_DIR="$ORCH_DIR/minecraft-server"
TEMPLATES_DIR="$ORCH_DIR/templates"
OUT_WIN="$ORCH_DIR/cohost-windows.zip"
OUT_MAC="$ORCH_DIR/cohost-mac.zip"

echo "========================================================"
echo "[Cohost Packager] Preparing pre-configured starter packs..."
echo "========================================================"

if [ ! -f "$SERVER_DIR/ServerLauncher.jar" ]; then
    echo "[ERROR] ServerLauncher.jar not found in $SERVER_DIR. Run mac-linux/build.sh first!"
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

stage_shared_files() {
    local target="$1"
    local files=(
        "rclone-filter.txt" "ServerLauncher.jar"
        "config.json" "eula.txt" "server.properties"
        "bukkit.yml" "spigot.yml" "commands.yml"
        "ops.json" "whitelist.json" "banned-ips.json" "banned-players.json"
    )
    for f in "${files[@]}"; do
        if [ -f "$SERVER_DIR/$f" ]; then
            cp "$SERVER_DIR/$f" "$target/"
        fi
    done
    [ -d "$SERVER_DIR/plugins" ] && cp -r "$SERVER_DIR/plugins" "$target/"
    [ -d "$SERVER_DIR/config" ] && cp -r "$SERVER_DIR/config" "$target/"
}

# 1. Package Windows
echo ""
echo "[1/2] Creating Windows pack (cohost-windows.zip)..."
STAGING_WIN=$(mktemp -d 2>/dev/null || mktemp -d -t 'mc_win')
TARGET_WIN="$STAGING_WIN/minecraft-server"
mkdir -p "$TARGET_WIN/bin"
mkdir -p "$TARGET_WIN/tools"

stage_shared_files "$TARGET_WIN"
[ -f "$SERVER_DIR/bin/core.dat" ] && cp "$SERVER_DIR/bin/core.dat" "$TARGET_WIN/bin/"
[ -f "$SERVER_DIR/bin/rclone.exe" ] && cp "$SERVER_DIR/bin/rclone.exe" "$TARGET_WIN/bin/"
[ -f "$SERVER_DIR/bin/icon.ico" ] && cp "$SERVER_DIR/bin/icon.ico" "$TARGET_WIN/bin/"

cp "$TEMPLATES_DIR/windows/START_SERVER.bat" "$TARGET_WIN/"
cp "$TEMPLATES_DIR/windows/Setup_Desktop_Shortcut.bat" "$TARGET_WIN/"
cp "$TEMPLATES_DIR/windows/tools/"* "$TARGET_WIN/tools/"

rm -f "$OUT_WIN"
(cd "$STAGING_WIN" && zip -r -q "$OUT_WIN" minecraft-server)
rm -rf "$STAGING_WIN"
echo "  [OK] cohost-windows.zip created"

# 2. Package Mac
echo ""
echo "[2/2] Creating macOS pack (cohost-mac.zip)..."
STAGING_MAC=$(mktemp -d 2>/dev/null || mktemp -d -t 'mc_mac')
TARGET_MAC="$STAGING_MAC/minecraft-server"
mkdir -p "$TARGET_MAC/bin"
mkdir -p "$TARGET_MAC/tools"

stage_shared_files "$TARGET_MAC"
[ -f "$SERVER_DIR/bin/core.dat" ] && cp "$SERVER_DIR/bin/core.dat" "$TARGET_MAC/bin/"
[ -f "$SERVER_DIR/bin/rclone" ] && cp "$SERVER_DIR/bin/rclone" "$TARGET_MAC/bin/"
[ -f "$SERVER_DIR/bin/icon.ico" ] && cp "$SERVER_DIR/bin/icon.ico" "$TARGET_MAC/bin/"

cp "$TEMPLATES_DIR/mac-linux/START_SERVER.command" "$TARGET_MAC/"
cp "$TEMPLATES_DIR/mac-linux/Setup_Mac_App.command" "$TARGET_MAC/"
chmod +x "$TARGET_MAC"/*.command 2>/dev/null || true
cp "$TEMPLATES_DIR/mac-linux/tools/"* "$TARGET_MAC/tools/"
chmod +x "$TARGET_MAC/tools/"*.command 2>/dev/null || true

rm -f "$OUT_MAC"
(cd "$STAGING_MAC" && zip -r -q "$OUT_MAC" minecraft-server)
rm -rf "$STAGING_MAC"
echo "  [OK] cohost-mac.zip created"

rm -f "$ORCH_DIR/cohost-starter-pack.zip"

echo ""
echo "========================================================"
echo "[SUCCESS] Starter packs created successfully!"
echo "  Windows Pack : $OUT_WIN"
echo "  Mac Pack     : $OUT_MAC"
echo "========================================================"
