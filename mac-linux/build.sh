#!/usr/bin/env bash
set -e
ORCH_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "========================================================"
echo "[MC Cluster Orchestrator] Building ServerLauncher..."
echo "========================================================"

# 1. Resolve destination directory
TARGET_DIR="$ORCH_DIR/minecraft-server"
if [ ! -d "$TARGET_DIR" ]; then
    echo "[INFO] 'minecraft-server' directory not found. Creating it at '$TARGET_DIR'..."
    mkdir -p "$TARGET_DIR"
fi

# 2. Compile ServerLauncher.java
echo "[1/3] Compiling ServerLauncher.java..."
rm -rf "$ORCH_DIR/build_out"
mkdir -p "$ORCH_DIR/build_out/META-INF"

javac --release 21 -encoding UTF-8 -d "$ORCH_DIR/build_out" "$ORCH_DIR/src/ServerLauncher.java"

# 3. Write Manifest & Package ServerLauncher.jar
echo "[2/3] Packaging ServerLauncher.jar..."
cat << 'EOF' > "$ORCH_DIR/build_out/META-INF/MANIFEST.MF"
Manifest-Version: 1.0
Main-Class: ServerLauncher

EOF

jar cfm "$TARGET_DIR/ServerLauncher.jar" "$ORCH_DIR/build_out/META-INF/MANIFEST.MF" -C "$ORCH_DIR/build_out" .
rm -rf "$ORCH_DIR/build_out"

if [ -f "$TARGET_DIR/ServerLauncher.jar" ]; then
    echo "Successfully built and deployed ServerLauncher.jar to $TARGET_DIR/"
else
    echo "[ERROR] Failed to package ServerLauncher.jar."
    exit 1
fi

# 4. Trigger ClusterGuard build if present
if [ -f "$ORCH_DIR/cluster-guard/build_guard.sh" ]; then
    echo ""
    echo "========================================================"
    echo "[MC Cluster Guard] Triggering ClusterGuard build..."
    echo "========================================================"
    bash "$ORCH_DIR/cluster-guard/build_guard.sh"
fi

# 5. Auto-provision operational templates into deployment folder
if [ -d "$ORCH_DIR/templates" ]; then
    echo ""
    echo "========================================================"
    echo "[MC Cluster Orchestrator] Deploying operational templates..."
    echo "========================================================"

    # Clean up legacy subdirectories if present
    rm -rf "$TARGET_DIR/windows" "$TARGET_DIR/mac-linux"

    # Deploy primary launchers to root
    [ -f "$ORCH_DIR/templates/windows/START_SERVER.bat" ] && cp -f "$ORCH_DIR/templates/windows/START_SERVER.bat" "$TARGET_DIR/START_SERVER.bat"
    [ -f "$ORCH_DIR/templates/windows/Setup_Desktop_Shortcut.bat" ] && cp -f "$ORCH_DIR/templates/windows/Setup_Desktop_Shortcut.bat" "$TARGET_DIR/Setup_Desktop_Shortcut.bat"
    [ -f "$ORCH_DIR/templates/mac-linux/START_SERVER.command" ] && cp -f "$ORCH_DIR/templates/mac-linux/START_SERVER.command" "$TARGET_DIR/START_SERVER.command" && chmod +x "$TARGET_DIR/START_SERVER.command"
    [ -f "$ORCH_DIR/templates/mac-linux/Setup_Mac_App.command" ] && cp -f "$ORCH_DIR/templates/mac-linux/Setup_Mac_App.command" "$TARGET_DIR/Setup_Mac_App.command" && chmod +x "$TARGET_DIR/Setup_Mac_App.command"
    echo "  [OK] Deployed primary launchers (START_SERVER and Shortcut Setups)"

    # Deploy tools/ folder
    mkdir -p "$TARGET_DIR/tools"
    [ -d "$ORCH_DIR/templates/windows/tools" ] && cp -f "$ORCH_DIR/templates/windows/tools/"* "$TARGET_DIR/tools/" 2>/dev/null || true
    [ -d "$ORCH_DIR/templates/mac-linux/tools" ] && cp -f "$ORCH_DIR/templates/mac-linux/tools/"* "$TARGET_DIR/tools/" 2>/dev/null || true
    chmod +x "$TARGET_DIR/tools/"*.command 2>/dev/null || true
    echo "  [OK] Deployed tools/ scripts"

    # Deploy root templates
    if [ -f "$ORCH_DIR/templates/icon.ico" ]; then
        mkdir -p "$TARGET_DIR/bin"
        cp -f "$ORCH_DIR/templates/icon.ico" "$TARGET_DIR/bin/icon.ico"
        echo "  [OK] Deployed bin/icon.ico"
    fi
    if [ -f "$ORCH_DIR/templates/rclone-filter.txt" ]; then
        cp -f "$ORCH_DIR/templates/rclone-filter.txt" "$TARGET_DIR/rclone-filter.txt"
        echo "  [OK] Deployed rclone-filter.txt"
    fi
    if [ ! -f "$TARGET_DIR/config.json" ] && [ -f "$ORCH_DIR/templates/config.example.json" ]; then
        cp "$ORCH_DIR/templates/config.example.json" "$TARGET_DIR/config.json"
        echo "  [OK] Initialized config.json (Please configure with your Backblaze B2 keys)"
    fi
    if [ ! -f "$TARGET_DIR/[template]config.json" ] && [ -f "$ORCH_DIR/templates/config.example.json" ]; then
        cp "$ORCH_DIR/templates/config.example.json" "$TARGET_DIR/[template]config.json"
        echo "  [OK] Deployed [template]config.json for co-hosts"
    fi
fi

echo ""
echo "========================================================"
echo "[SUCCESS] Full cluster build completed!"
echo "  [OK] ServerLauncher.jar : $TARGET_DIR/ServerLauncher.jar"
if [ -f "$TARGET_DIR/plugins/ClusterGuard.jar" ]; then
    echo "  [OK] ClusterGuard.jar   : $TARGET_DIR/plugins/ClusterGuard.jar"
fi
echo "========================================================"

