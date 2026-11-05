# MC Cluster Orchestrator

A lightweight, peer-to-peer distributed Minecraft server orchestrator. Coordinates active hosting across multiple personal machines without expensive 24/7 dedicated cloud hosting, while preventing world desync and split-brain corruption.

---

## Capabilities & User Experience

- **Zero-Friction Player Access:** Players connect via standard vanilla Minecraft to a single, static address (e.g. `your-cluster.ply.gg`). No client VPNs, Hamachi, or third-party software are required for players.
- **Cross-Platform Support:** Orchestrator and fail-safe components run natively across **Windows, macOS, and Linux**.
- **High Availability Without Cloud Costs:** Any authorized co-host in the cluster can boot and run the server when the primary host is offline. Players always connect to the exact same address regardless of who is hosting.
- **Zero Risk of World Desynchronization:** Strict distributed locking ensures that two co-hosts can never run the server simultaneously. World saves, inventory, player stats, and dimensions are guaranteed to be 100% converged before the server boots.
- **Bypasses Strict CGNAT, Campus & Double-NAT Firewalls:** Solves institutional networks, university Wi-Fi restrictions, double-NAT, and AP client isolation using dedicated Anycast tunneling.

---

## Core Features

- **Distributed Lock Management:** Coordinates the active host via `.server_active.lock` replicated through Syncthing. Includes automated stale-lock detection and expiration if an active host's machine abruptly loses power or crashes.
- **Telemetry Heartbeat:** Runs an asynchronous background daemon that periodically updates the lock timestamp while the server process is healthy.
- **State Convergence Check:** Communicates with the local Syncthing REST API (`/rest/db/status`) to ensure incoming file deltas reach 0 bytes before allowing the server to launch. Automatically triggers a sync scan (`/rest/db/scan`) upon server shutdown.
- **Safe-Launch Fail-Safe Plugin (`ClusterGuard`):** A custom Paper/Spigot Java plugin compiled with Java 21 that inspects the `-Dcluster.safe_launch=true` runtime property. If anyone attempts to double-click the server JAR directly, ClusterGuard aborts boot immediately to prevent unsynchronized state divergence.
- **Zero-Port-Forward Anycast Ingress (Playit.gg):** Utilizes an outbound encrypted Anycast tunnel plugin. The tunnel credentials reside within the synced `plugins/` directory, allowing the permanent domain to follow whichever cluster host boots the server.
- **Automated Deployment Pipeline:** `build.bat` compiles the orchestrator and fail-safe plugin, creates the server folder if missing, and auto-provisions all operational templates (`run_server.bat`, `run_server.sh`, `.stignore`, `config.json`, `[template]config.json`).
- **Emergency Lock Release Tools:** Dedicated cross-platform administrative scripts (`force_unlock.bat`, `force_unlock.sh`, and `unlock.ps1`) located at the repository root to manually release stranded locks without touching running server processes.
- **Dynamic Mesh DNS (Optional / Fallback):** Built-in support for resolving the local node's Tailscale IPv4 interface and updating DuckDNS records upon lock acquisition for private mesh networks.

---

## Repository & Deployment Architecture

> **Note for Admins:** This repository contains only the orchestration engine, build scripts, fail-safe plugin, and operational templates. Operational server binaries, world saves, and runtime data are strictly excluded via `.gitignore` and are provided by you.

```text
mc-cluster/
├── mc-cluster-orchestrator/            <-- [DEV / GIT REPO]
│   ├── .gitignore
│   ├── pom.xml                         <-- Root Maven project descriptor
│   ├── README.md
│   ├── build.bat                       <-- Compiles & auto-deploys (Windows)
│   ├── build.sh                        <-- Compiles & auto-deploys (Linux/macOS)
│   ├── package_jar.ps1                 <-- Utility script for packaging jars
│   ├── force_unlock.bat                <-- Admin emergency lock release (Windows Batch)
│   ├── force_unlock.sh                 <-- Admin emergency lock release (Linux/macOS Bash)
│   ├── unlock.ps1                      <-- Admin emergency lock release (PowerShell)
│   ├── src/
│   │   └── ServerLauncher.java         <-- Orchestrator & distributed lock manager
│   ├── cluster-guard/                  <-- Safe-launch fail-safe plugin source
│   │   ├── pom.xml
│   │   ├── build_guard.bat             <-- Plugin compile script (Windows)
│   │   ├── build_guard.sh              <-- Plugin compile script (Linux/macOS)
│   │   └── src/main/java/com/cluster/guard/ClusterGuard.java
│   └── templates/                      <-- Pre-configured operational templates
│       ├── .stignore                   <-- Syncthing replication filter
│       ├── config.example.json         <-- Cluster configuration template
│       ├── run_server.bat              <-- Host startup script (Windows)
│       └── run_server.sh               <-- Host startup script (Linux/macOS)
│
└── minecraft-server/                   <-- [YOUR SERVER ROOT / SYNCTHING SHARE]
    ├── .stignore                       <-- Auto-deployed by build.bat
    ├── config.json                     <-- Auto-deployed by build.bat (Edit with your key)
    ├── [template]config.json           <-- Auto-deployed by build.bat for co-hosts
    ├── run_server.bat                  <-- Auto-deployed by build.bat (Windows)
    ├── run_server.sh                   <-- Auto-deployed by build.bat (Linux/macOS)
    ├── ServerLauncher.jar              <-- Built & deployed by build.bat
    ├── server.properties
    ├── bin/
    │   └── core.dat                    <-- Your Paper server jar renamed to core.dat
    ├── plugins/
    │   ├── ClusterGuard.jar            <-- Built & deployed by build.bat
    │   └── playit-minecraft-plugin.jar <-- High-speed Anycast tunnel plugin
    └── world/                          <-- Game save files (Overworld, Nether, End)
```

---

## Setup Instructions for the Cluster Admin (Initial Setup)

As the admin, you are converting an existing (or fresh) Minecraft Paper server into a distributed peer-to-peer cluster.

### 1. Prerequisites (Cross-Platform)
- **Java 21:** [Adoptium Temurin 21](https://adoptium.net/temurin/releases/?version=21) (available for Windows, macOS, and Linux).
- **Syncthing:** [Syncthing Downloads](https://syncthing.net/downloads/)
  - **Windows:** [SyncTrayzor](https://github.com/canton7/SyncTrayzor/releases/latest) or standard Syncthing.
  - **macOS:** [Syncthing for macOS](https://github.com/syncthing/syncthing-macos/releases) or `brew install syncthing`.
  - **Linux:** Install via package manager (`sudo apt install syncthing`, `pacman -S syncthing`, etc.).
  - *Note: On all platforms, the Syncthing Web GUI is accessible at `http://127.0.0.1:8384`.*
- A **Paper Minecraft Server Jar** (or your existing server folder).

### 2. Set Up the Server Folder & Core Binary
1. In the repository root, create a folder named **`minecraft-server`** (or move your existing Minecraft server folder here and rename it to `minecraft-server`).
   *(This folder is ignored by `.gitignore` so your world, binaries, and local configs will never be tracked by Git).*
2. Inside `minecraft-server/`, create a subfolder named **`bin/`**.
3. Move your Paper server JAR file into `bin/` and rename it to **`core.dat`**:
   ```text
   minecraft-server/bin/core.dat
   ```
   *(Renaming the core binary prevents accidental direct launches that bypass cluster synchronization).*

### 3. Run the Automated Build & Deployment
From the root of `mc-cluster-orchestrator`:
- **Windows:**
  ```cmd
  .\build.bat
  ```
- **Linux / macOS:**
  ```bash
  chmod +x build.sh cluster-guard/build_guard.sh
  ./build.sh
  ```
The build script automates the entire provisioning process:
- Compiles `ServerLauncher.java` $\rightarrow$ deploys `minecraft-server/ServerLauncher.jar`.
- Compiles `ClusterGuard` $\rightarrow$ deploys `minecraft-server/plugins/ClusterGuard.jar`.
- **Auto-Deploys Operational Templates:** Automatically copies `run_server.bat`, `run_server.sh`, `.stignore`, `config.json`, and `[template]config.json` from `templates/` directly into `minecraft-server/` (existing configs are safely preserved).

### 4. Configure Your Syncthing Settings
1. Open `minecraft-server/config.json` in a text editor:
   - In Syncthing (`http://127.0.0.1:8384`), go to **Actions $\rightarrow$ Settings $\rightarrow$ General** and copy your **API Key**.
   - Paste it into `"api_key": "YOUR_SYNCTHING_API_KEY"`.
   - Set `"folder_id"` to your chosen cluster sync ID (e.g. `"mc-server-cluster"`).
   - Adjust `"java_memory"` to your allocated RAM (e.g. `"6G"`).
2. Save and close `config.json`.
3. In Syncthing (`http://127.0.0.1:8384`), click **Add Folder**, select your `minecraft-server/` directory, and set the **Folder ID** to match `config.json` (e.g. `mc-server-cluster`).

### 5. Configure Playit.gg Ingress (One-Time)
1. Download [`playit-minecraft-plugin.jar`](https://playit.gg/download/minecraft) and drop it into `minecraft-server/plugins/`.
2. Start the server (Windows: `run_server.bat` | Linux/macOS: `./run_server.sh`).
3. Check the console for the 1-click claim link:
   ```text
   [playit-gg]: Visit https://playit.gg/claim/xxxx to claim your server
   ```
4. Click the link in your browser, select **Add Tunnel $\rightarrow$ Minecraft Java**, and claim your permanent domain (e.g. `your-cluster.ply.gg`).
5. Playit automatically saves your credentials into `plugins/playit-gg/config.yml`. Because `plugins/` is synchronized via Syncthing, your co-hosts will automatically inherit this tunnel!

---

## Setup Instructions for Co-Hosts (New Hosters)

Co-hosts do not need Git, compilers, or developer tools. They simply sync the runtime files from the Admin via Syncthing.

### 1. Install Prerequisites (Cross-Platform)
- **Java 21:** [Adoptium Temurin 21](https://adoptium.net/temurin/releases/?version=21) (Windows, macOS, or Linux).
- **Syncthing:**
  - **Windows:** [SyncTrayzor](https://github.com/canton7/SyncTrayzor/releases/latest)
  - **macOS:** [Syncthing for macOS](https://github.com/syncthing/syncthing-macos/releases) or `brew install syncthing`
  - **Linux:** Install via package manager (`sudo apt install syncthing`, `dnf install syncthing`, etc.)

### 2. Connect Syncthing
1. Open the Syncthing Web GUI (`http://127.0.0.1:8384`), go to **Actions** (top right) $\rightarrow$ **Show ID**, and send your Device ID to the Cluster Admin.
2. Once the Admin adds you, accept the incoming folder request for **`mc-server-cluster`**.
3. Choose a destination folder on your machine (e.g. `C:\Games\minecraft-server` or `~/minecraft-server`) and let Syncthing download the files.

### 3. Create Local Configuration
1. In your synced `minecraft-server` folder, locate `[template]config.json` (auto-deployed by the admin's build).
2. Duplicate it and rename the copy to **`config.json`**.
3. Open `config.json` in a text editor:
   - In Syncthing (`http://127.0.0.1:8384`), go to **Actions $\rightarrow$ Settings $\rightarrow$ General** and copy your **API Key**.
   - Paste it into `"api_key": "YOUR_LOCAL_SYNCTHING_API_KEY_HERE"`.
   - Adjust `"java_memory": "6G"` to match your machine's available RAM (e.g. `4G`, `6G`, or `8G`).
4. Save and close `config.json`.

---

## Operating the Cluster

### Starting the Server
- **Windows:** Double-click **`run_server.bat`** in `minecraft-server/`.
- **Linux / macOS:** Run `chmod +x run_server.sh && ./run_server.sh` in `minecraft-server/`.

1. The script auto-starts the Syncthing background daemon if it is not already running.
2. `ServerLauncher` verifies no other cluster node is active and acquires `.server_active.lock`.
3. Pre-boot synchronization confirms that all world chunks are identical to the latest host's save.
4. The server launches and binds the permanent Playit domain.

### Stopping the Server
In the server terminal window, type:
```text
stop
```
The server will save all chunks, flush region files, cleanly release `.server_active.lock`, and trigger an outbound Syncthing scan to replicate your changes across the cluster.

### Administrative Tools: Emergency Lock Release
If a host's machine abruptly crashes and a co-host needs to immediately start the server before the 10-minute stale timeout expires, the cluster admin can run:
- **Windows Batch:** Double-click `force_unlock.bat` in the repository root.
- **PowerShell:** Execute `.\unlock.ps1` from the repository root.
- **Linux / macOS Bash:** Run `chmod +x force_unlock.sh && ./force_unlock.sh` from the repository root.

The script scans standard deployment locations, removes stranded `.server_active.lock` files, and re-opens the cluster for hosting.
