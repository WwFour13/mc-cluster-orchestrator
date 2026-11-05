#!/usr/bin/env bash
cd "$(dirname "$0")"

SERVER_DIR="$(pwd)"
APP_DIR="$HOME/Desktop/Minecraft Cluster Server.app"

echo "========================================================"
echo " Installing Minecraft Cluster Server Mac App..."
echo "========================================================"

mkdir -p "$APP_DIR/Contents/MacOS"
mkdir -p "$APP_DIR/Contents/Resources"

cat << 'EOF' > "$APP_DIR/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>run.sh</string>
    <key>CFBundleName</key>
    <string>Minecraft Cluster Server</string>
    <key>CFBundleIdentifier</key>
    <string>com.minecraft.cluster</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
</dict>
</plist>
EOF

cat << EOF > "$APP_DIR/Contents/MacOS/run.sh"
#!/usr/bin/env bash
osascript -e 'tell application "Terminal" to do script "cd \"$SERVER_DIR\" && ./run_server.sh"'
EOF

chmod +x "$APP_DIR/Contents/MacOS/run.sh"

echo ""
echo "========================================================"
echo " [SUCCESS] Minecraft Cluster Server.app created on your Desktop!"
echo "========================================================"
echo ""
echo " 1. Desktop: Double-click 'Minecraft Cluster Server' on your Desktop."
echo " 2. Dock:    Drag 'Minecraft Cluster Server.app' into your Mac Dock to pin it!"
echo " 3. Search:  Press Cmd+Space and type 'Minecraft Cluster' to launch anytime."
echo ""
