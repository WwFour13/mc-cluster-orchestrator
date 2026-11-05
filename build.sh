#!/usr/bin/env bash
set -e
ORCH_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "========================================================"
echo "[MC Cluster Orchestrator] Building ServerLauncher..."
echo "========================================================"

# 1. Resolve destination directory
TARGET_DIR=""
if [ -d "$ORCH_DIR/../minecraft-server" ]; then
    TARGET_DIR="$ORCH_DIR/../minecraft-server"
elif [ -d "$ORCH_DIR/minecraft-server" ]; then
    TARGET_DIR="$ORCH_DIR/minecraft-server"
else
    echo "[INFO] 'minecraft-server' directory not found. Creating it at '$ORCH_DIR/minecraft-server'..."
    mkdir -p "$ORCH_DIR/minecraft-server"
    TARGET_DIR="$ORCH_DIR/minecraft-server"
fi

# 2. Compile ServerLauncher.java
echo "[1/3] Compiling ServerLauncher.java..."
rm -rf "$ORCH_DIR/build_out"
mkdir -p "$ORCH_DIR/build_out/META-INF"

javac -encoding UTF-8 -d "$ORCH_DIR/build_out" "$ORCH_DIR/src/ServerLauncher.java"

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

# 4. Trigger ClusterGuard build
if [ -f "$ORCH_DIR/cluster-guard/build_guard.sh" ]; then
    echo ""
    echo "========================================================"
    echo "[MC Cluster Guard] Triggering ClusterGuard build..."
    echo "========================================================"
    bash "$ORCH_DIR/cluster-guard/build_guard.sh"
fi

# 5. Auto-provision operational templates
if [ -d "$ORCH_DIR/templates" ]; then
    echo ""
    echo "========================================================"
    echo "[MC Cluster Orchestrator] Deploying operational templates..."
    echo "========================================================"
    if [ -f "$ORCH_DIR/templates/Setup_Desktop_Shortcut.bat" ]; then
        cp -f "$ORCH_DIR/templates/Setup_Desktop_Shortcut.bat" "$TARGET_DIR/Setup_Desktop_Shortcut.bat"
        echo "  [OK] Deployed Setup_Desktop_Shortcut.bat"
    fi
    if [ -f "$ORCH_DIR/templates/setup_shortcut.ps1" ]; then
        cp -f "$ORCH_DIR/templates/setup_shortcut.ps1" "$TARGET_DIR/setup_shortcut.ps1"
        echo "  [OK] Deployed setup_shortcut.ps1"
    fi
    if [ -f "$ORCH_DIR/templates/Setup_Mac_App.sh" ]; then
        cp -f "$ORCH_DIR/templates/Setup_Mac_App.sh" "$TARGET_DIR/Setup_Mac_App.sh"
        chmod +x "$TARGET_DIR/Setup_Mac_App.sh" 2>/dev/null || true
        echo "  [OK] Deployed Setup_Mac_App.sh"
    fi
    if [ -f "$ORCH_DIR/templates/icon.ico" ]; then
        mkdir -p "$TARGET_DIR/bin"
        cp -f "$ORCH_DIR/templates/icon.ico" "$TARGET_DIR/bin/icon.ico"
        echo "  [OK] Deployed bin/icon.ico"
    fi
    if [ -f "$ORCH_DIR/templates/run_server.sh" ]; then
        cp -f "$ORCH_DIR/templates/run_server.sh" "$TARGET_DIR/run_server.sh"
        chmod +x "$TARGET_DIR/run_server.sh" 2>/dev/null || true
        echo "  [OK] Deployed run_server.sh (Linux/macOS)"
    fi
    if [ -f "$ORCH_DIR/templates/run_server.bat" ]; then
        cp -f "$ORCH_DIR/templates/run_server.bat" "$TARGET_DIR/run_server.bat"
        echo "  [OK] Deployed run_server.bat (Windows)"
    fi
    if [ -f "$ORCH_DIR/templates/sync_pull.sh" ]; then
        cp -f "$ORCH_DIR/templates/sync_pull.sh" "$TARGET_DIR/sync_pull.sh"
        chmod +x "$TARGET_DIR/sync_pull.sh" 2>/dev/null || true
        echo "  [OK] Deployed sync_pull.sh"
    fi
    if [ -f "$ORCH_DIR/templates/sync_push.sh" ]; then
        cp -f "$ORCH_DIR/templates/sync_push.sh" "$TARGET_DIR/sync_push.sh"
        chmod +x "$TARGET_DIR/sync_push.sh" 2>/dev/null || true
        echo "  [OK] Deployed sync_push.sh"
    fi
    if [ -f "$ORCH_DIR/templates/sync_pull.bat" ]; then
        cp -f "$ORCH_DIR/templates/sync_pull.bat" "$TARGET_DIR/sync_pull.bat"
        echo "  [OK] Deployed sync_pull.bat"
    fi
    if [ -f "$ORCH_DIR/templates/sync_push.bat" ]; then
        cp -f "$ORCH_DIR/templates/sync_push.bat" "$TARGET_DIR/sync_push.bat"
        echo "  [OK] Deployed sync_push.bat"
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

