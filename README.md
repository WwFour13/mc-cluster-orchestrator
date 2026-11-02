# MC Cluster Orchestrator

Distributed Minecraft server orchestrator for peer-to-peer game hosting across multiple nodes.

## Features
- **Distributed Lock Management:** Coordinates active host using `.server_active.lock` synced via Syncthing, with automatic stale lock expiration.
- **Dynamic Mesh DNS:** Updates DuckDNS with the local node's Tailscale IPv4 interface upon acquiring lock.
- **Pre-boot & Teardown State Sync:** Coordinates Syncthing scans and waits for replication convergence before starting and after stopping the server.
- **Telemetry Heartbeat:** Periodically refreshes the lock timestamp in background while hosting.

## Structure
```
mc-cluster/
├── mc-cluster-orchestrator/            <-- [DEV / GIT REPO]
│   ├── .gitignore
│   ├── config.example.json
│   ├── README.md
│   ├── build.bat
│   └── src/
│       └── ServerLauncher.java
│
└── minecraft-server/                   <-- [OPERATIONAL / SYNCTHING]
    ├── .stignore
    ├── config.json
    ├── eula.txt
    ├── run_server.bat
    ├── ServerLauncher.jar              <-- Built output copied from orchestrator
    ├── bin/
    │   └── core.dat                    <-- Your Paper server jar renamed to core.dat
    └── world/                          <-- Created on first run
```

## Build
Run `build.bat` to compile `ServerLauncher.java` into `ServerLauncher.jar`. The script automatically copies the built JAR into `../minecraft-server/`.
