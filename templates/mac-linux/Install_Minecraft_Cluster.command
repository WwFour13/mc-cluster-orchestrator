#!/usr/bin/env bash
cd "$(dirname "$0")"

echo "========================================================"
echo "        MINECRAFT CLUSTER ORCHESTRATOR INSTALLER"
echo "========================================================"
echo ""

# 1. Check Java
echo "[1/3] Checking Java runtime environment..."
JAVA_VALID=0

if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{if ($1 == "1") print $2; else print $1}')
    if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
        JAVA_VALID=1
        echo "  [OK] Detected compatible Java $JAVA_VER installation."
    fi
fi

if [ "$JAVA_VALID" -eq 0 ]; then
    echo ""
    echo "  [!] Java 17+ or 21+ is required for hosting modern Minecraft servers."
    echo ""
    if command -v brew >/dev/null 2>&1; then
        echo "  [>] Homebrew detected. Installing OpenJDK 21..."
        brew install openjdk@21
        sudo ln -sfn "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk 2>/dev/null
    else
        echo "  [>] Opening Eclipse Adoptium Java 21 LTS download page in your browser..."
        open "https://adoptium.net/temurin/releases/?version=21" 2>/dev/null || xdg-open "https://adoptium.net/temurin/releases/?version=21" 2>/dev/null
        read -p "  Press [Enter] after installing Java to continue..."
    fi
fi

# 2. Setup Mac App / Desktop Shortcut
echo ""
echo "[2/3] Creating native application shortcut..."
if [ -f "./Setup_Mac_App.command" ]; then
    chmod +x "./Setup_Mac_App.command"
    ./Setup_Mac_App.command
fi

# 3. Launch Server
echo ""
echo "[3/3] Launching Minecraft Cluster..."
chmod +x "./START_SERVER.command"
open "./START_SERVER.command" 2>/dev/null || ./START_SERVER.command &

echo ""
echo "========================================================"
echo " [SUCCESS] Setup complete!"
echo "========================================================"
