#!/usr/bin/env bash
cd "$(dirname "$0")"

# Apply pending update
if [ -f "ServerLauncher.jar.update" ]; then
    mv -f "ServerLauncher.jar.update" "ServerLauncher.jar"
fi

if [ ! -f "ServerLauncher.jar" ]; then
    echo "[ERROR] ServerLauncher.jar not found!"
    exit 1
fi

java -jar ServerLauncher.jar "$@"
EXIT_CODE=$?

if [ "$EXIT_CODE" -eq 100 ] || [ -f "ServerLauncher.jar.update" ]; then
    if [ -f "ServerLauncher.jar.update" ]; then
        mv -f "ServerLauncher.jar.update" "ServerLauncher.jar"
    fi
    exec "$0" "$@"
fi
