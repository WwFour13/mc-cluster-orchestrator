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
    <string>2.0.0</string>
</dict>
</plist>
EOF

cat << EOF > "$APP_DIR/Contents/MacOS/run.sh"
#!/usr/bin/env bash

# Check Java 21
JAVA_VER=0
if command -v java >/dev/null 2>&1; then
    JAVA_VER=\$(java -version 2>&1 | awk -F '"' '/version/ {print \$2}' | awk -F '.' '{if (\$1 == "1") print \$2; else print \$1}')
fi

if [ "\$JAVA_VER" -lt 17 ] 2>/dev/null; then
    CHOICE=\$(osascript -e 'display dialog "Java 21 LTS is required for Minecraft server hosting.\nWould you like to open the official Eclipse Adoptium download page?" buttons {"Cancel", "Download Java 21"} default button 2 with title "Java 21 Required"')
    if [[ "\$CHOICE" == *"Download"* ]]; then
        open "https://adoptium.net/temurin/releases/?version=21"
    fi
    exit 1
fi

osascript -e "tell application \"Terminal\" to do script \"cd \\\"$SERVER_DIR\\\" && ./LAUNCH_ORCHESTRATOR.command\""
EOF

chmod +x "$APP_DIR/Contents/MacOS/run.sh"
chmod +x "$SERVER_DIR/LAUNCH_ORCHESTRATOR.command"

echo ""
echo "========================================================"
echo " [SUCCESS] Minecraft Cluster Server.app created on your Desktop!"
echo "========================================================"
echo ""
echo " 1. Desktop: Double-click 'Minecraft Cluster Server' on your Desktop."
echo " 2. Dock:    Drag 'Minecraft Cluster Server.app' into your Mac Dock to pin it!"
echo " 3. Search:  Press Cmd+Space and type 'Minecraft Cluster' to launch anytime."
echo ""
