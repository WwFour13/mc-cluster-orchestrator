#!/usr/bin/env bash
cd "$(dirname "$0")"

# 1. Verify orchestrator binary exists
if [ ! -f "ServerLauncher.jar" ]; then
    echo "[ERROR] ServerLauncher.jar not found!"
    echo "Run ./build.sh in mc-cluster-orchestrator to generate it."
    exit 1
fi

# 2. Launch cluster orchestrator (handles cloud lock, cloud pull, runtime, autosave, and cloud push)
java -jar ServerLauncher.jar
