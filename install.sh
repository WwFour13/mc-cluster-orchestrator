#!/usr/bin/env bash
set -e

echo "========================================================"
echo "        MINECRAFT CLUSTER ORCHESTRATOR INSTALLER"
echo "========================================================"
echo ""

GITHUB_REPO="WwFour13/mc-cluster-orchestrator"
INSTALL_DIR="$HOME/minecraft-cluster"

# 1. Java 25 Check & Auto-Install
echo "[1/4] Verifying Java 25 runtime..."
JAVA_VER=0
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{if ($1 == "1") print $2; else print $1}')
fi

if [ "$JAVA_VER" -lt 25 ] 2>/dev/null; then
    echo "  [!] Java 25+ required for PaperMC 26.3. Installing OpenJDK 25 via package manager..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update && sudo apt-get install -y openjdk-25-jre-headless || sudo apt-get install -y openjdk-25-jdk
    elif command -v dnf >/dev/null 2>&1; then
        sudo dnf install -y java-25-openjdk
    elif command -v pacman >/dev/null 2>&1; then
        sudo pacman -Sy --noconfirm jre25-openjdk
    elif command -v zypper >/dev/null 2>&1; then
        sudo zypper install -y java-25-openjdk
    else
        echo "  [ERROR] Supported package manager not found. Please install OpenJDK 25 LTS manually from:"
        echo "  https://adoptium.net/temurin/releases/?version=25"
        exit 1
    fi
else
    echo "  [OK] Detected compatible Java $JAVA_VER."
fi

# 2. Prepare Directory
echo ""
echo "[2/4] Setting up installation directory at $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR/bin"
mkdir -p "$INSTALL_DIR/tools"

# 3. Fetch Runtime Assets from GitHub
echo ""
echo "[3/4] Downloading latest orchestrator binary..."
curl -fsSL -o "$INSTALL_DIR/ServerLauncher.jar" "https://github.com/$GITHUB_REPO/releases/latest/download/ServerLauncher.jar" 2>/dev/null || \
curl -fsSL -o "$INSTALL_DIR/ServerLauncher.jar" "https://raw.githubusercontent.com/$GITHUB_REPO/master/minecraft-server/ServerLauncher.jar"

# Write root launcher
cat << 'EOF' > "$INSTALL_DIR/START_SERVER.sh"
#!/usr/bin/env bash
cd "$(dirname "$0")"

while true; do
    if [ -f "ServerLauncher.jar.update" ]; then
        echo "[UPDATE] Applying ServerLauncher update..."
        mv -f "ServerLauncher.jar.update" "ServerLauncher.jar"
    fi

    if [ ! -f "ServerLauncher.jar" ]; then
        echo "[ERROR] ServerLauncher.jar not found!"
        exit 1
    fi

    java -jar ServerLauncher.jar "$@"
    EXIT_CODE=$?

    if [ "$EXIT_CODE" -ne 100 ] && [ ! -f "ServerLauncher.jar.update" ]; then
        break
    fi
    echo "[RESTART] Applying update and relaunching..."
    sleep 1
done
EOF
chmod +x "$INSTALL_DIR/START_SERVER.sh"

# 4. Create Desktop Entry (.desktop)
echo ""
echo "[4/4] Creating Linux application launcher (.desktop)..."
DESKTOP_DIR="$HOME/.local/share/applications"
mkdir -p "$DESKTOP_DIR"

cat << EOF > "$DESKTOP_DIR/minecraft-cluster.desktop"
[Desktop Entry]
Type=Application
Name=Minecraft Cluster Server
Comment=Distributed Peer-to-Peer Minecraft Server Platform
Exec=$INSTALL_DIR/START_SERVER.sh
Icon=utilities-terminal
Terminal=true
Categories=Game;Network;
EOF

chmod +x "$DESKTOP_DIR/minecraft-cluster.desktop"

echo ""
echo "========================================================"
echo " [SUCCESS] Minecraft Cluster Orchestrator installed!"
echo " Location: $INSTALL_DIR"
echo " Search for 'Minecraft Cluster Server' in your App Menu."
echo "========================================================"
echo ""
echo "Launching Minecraft Cluster..."
cd "$INSTALL_DIR"
./START_SERVER.sh
