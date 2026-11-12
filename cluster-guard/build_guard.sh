#!/usr/bin/env bash
set -e
GUARD_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "========================================================"
echo "[ClusterGuard] Compiling Fail-Safe Guard Plugin..."
echo "========================================================"

# 1. Resolve Classpath: core binary, libraries, or bundled paper-api
CP=""
if [ -f "$GUARD_DIR/../../minecraft-server/bin/core.dat" ]; then
    CP="$GUARD_DIR/../../minecraft-server/bin/core.dat"
elif [ -f "$GUARD_DIR/../minecraft-server/bin/core.dat" ]; then
    CP="$GUARD_DIR/../minecraft-server/bin/core.dat"
fi

if [ -f "$GUARD_DIR/lib/paper-api-1.20.4.jar" ]; then
    if [ -z "$CP" ]; then
        CP="$GUARD_DIR/lib/paper-api-1.20.4.jar"
    else
        CP="$CP:$GUARD_DIR/lib/paper-api-1.20.4.jar"
    fi
fi

echo "[1/3] Using API classpath: $CP"

# 2. Resolve destination
PLUGINS_DEST=""
if [ -d "$GUARD_DIR/../../minecraft-server/plugins" ]; then
    PLUGINS_DEST="$GUARD_DIR/../../minecraft-server/plugins"
elif [ -d "$GUARD_DIR/../minecraft-server/plugins" ]; then
    PLUGINS_DEST="$GUARD_DIR/../minecraft-server/plugins"
else
    mkdir -p "$GUARD_DIR/../minecraft-server/plugins"
    PLUGINS_DEST="$GUARD_DIR/../minecraft-server/plugins"
fi

# 3. Prepare build output directory & compile
rm -rf "$GUARD_DIR/target-build"
mkdir -p "$GUARD_DIR/target-build"

if [ -n "$CP" ]; then
    echo "[2/3] Compiling ClusterGuard.java..."
    javac --release 21 -encoding UTF-8 -cp "$CP" -d "$GUARD_DIR/target-build" "$GUARD_DIR/src/main/java/com/cluster/guard/ClusterGuard.java"
    cp "$GUARD_DIR/src/main/resources/plugin.yml" "$GUARD_DIR/target-build/plugin.yml"
    
    echo "[3/3] Packaging and deploying ClusterGuard.jar..."
    jar cf "$PLUGINS_DEST/ClusterGuard.jar" -C "$GUARD_DIR/target-build" .
    rm -rf "$GUARD_DIR/target-build"
else
    # Fallback to Maven if core.dat is not yet placed
    if command -v mvn >/dev/null 2>&1; then
        echo "[2/3] Resolving dependencies and compiling via Maven..."
        mvn -f "$GUARD_DIR/pom.xml" clean package -DskipTests
        cp -f "$GUARD_DIR/target/ClusterGuard.jar" "$PLUGINS_DEST/ClusterGuard.jar"
    else
        echo "[ERROR] API classpath not found (core.dat or paper-api missing)."
        echo "Please place your Paper server jar in minecraft-server/bin/core.dat or install Maven."
        exit 1
    fi
fi

echo "[SUCCESS] ClusterGuard plugin built and deployed to $PLUGINS_DEST/ClusterGuard.jar"

