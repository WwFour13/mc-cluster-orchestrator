# MC Cluster Orchestrator

A lightweight, distributed Minecraft server orchestrator. Coordinates active hosting across multiple personal machines without expensive 24/7 dedicated cloud hosting, while preventing world desync and split-brain corruption using **Backblaze B2** (Cloud Hub) or **Syncthing** (P2P Mesh).

---

## Capabilities & User Experience

- **Zero-Friction Player Access:** Players connect via standard vanilla Minecraft to a single, static address (e.g. `your-cluster.ply.gg`). No client VPNs, Hamachi, or third-party software are required for players.
- **Cross-Platform Support:** Orchestrator and fail-safe components run natively across **Windows, macOS, and Linux**.
- **Asynchronous Cloud Sync (No Machine Overlap Needed):** Uses a free Backblaze B2 bucket (10 GB free) so hosts do not need to be online at the same time to pass the world. Host A can upload and turn off their PC; Host B can wake up hours later, pull the latest world, and host.
- **Zero Background Daemons:** No programs (like Syncthing) need to run in the background 24/7. Rclone only executes when starting, stopping, or running scheduled autosaves.
- **Optimistic Concurrency Control (OCC):** Every session is tagged with a sequential Generation Number (`manifest.json`). Stale hosts are physically blocked from pushing old worlds over newer cloud progress.
- **Premature Pull Protection:** `sync_pull` automatically detects if another host is currently playing and aborts to prevent downloading incomplete, mid-game chunk sectors.
- **Mid-Game Crash Safety:** Flushes dirty chunks (`save-all flush`) and runs incremental snapshot uploads to Backblaze B2 every 15 minutes during gameplay. If a host's computer dies, at most 15 minutes of progress is lost.
- **Fail-Safe Rollback (30-Day Versioning):** Backblaze B2 natively keeps previous revisions of chunk files for 30 days for free, making accidental corruption or player griefing instantly reversible.
- **Bypasses Strict CGNAT, Campus & Double-NAT Firewalls:** Solves institutional networks, university Wi-Fi restrictions, double-NAT, and AP client isolation using dedicated Anycast tunneling via Playit.gg.

---

## Core Features & Safety Guardrails

| Safety Guard | How It Works |
| :--- | :--- |
| **Distributed Cloud Lock** | Coordinates the active host via `cluster.lock` in Backblaze B2. If a co-host tries to start while someone is hosting, boot is safely aborted. |
| **Premature Pull Guard** | `sync_pull` checks `cluster.lock` and refuses to download if someone is currently hosting, preventing partial or torn writes. |
| **Generation Sequence Guard** | Tracks `generation: #N` in `manifest.json`. If a co-host with an outdated local world attempts to push, the push is hard-rejected until they pull. |
| **`--update` Timestamp Guard** | Rclone strictly skips files on the cloud that have newer timestamps than the local files being uploaded. |
| **Heartbeat & Stale Reclaim** | The active host updates the lock timestamp every 120s. If a host's machine loses power, the lock auto-expires after 10 minutes so other co-hosts are not trapped. |
| **In-Game Incremental Push** | Every 15 minutes, Paper runs `save-all flush` and uploads an incremental delta of modified `.mca` region files in the background without lag. |
| **ClusterGuard Fail-Safe Plugin** | A custom Paper/Spigot Java plugin that prevents direct JAR launches that bypass cluster orchestration. |

---

## Repository & Deployment Architecture

```text
mc-cluster-orchestrator/            <-- [DEV / GIT REPO]
├── .gitignore
├── pom.xml
├── README.md
├── build.bat                       <-- Compiles & auto-deploys (Windows)
├── build.sh                        <-- Compiles & auto-deploys (Linux/macOS)
├── force_unlock.bat                <-- Admin emergency lock release (Windows Batch)
├── force_unlock.sh                 <-- Admin emergency lock release (Linux/macOS Bash)
├── unlock.ps1                      <-- Admin emergency lock release (PowerShell)
├── src/
│   └── ServerLauncher.java         <-- Orchestrator, cloud lock manager, and sync engine
├── cluster-guard/                  <-- Safe-launch fail-safe plugin source
└── templates/                      <-- Operational templates deployed to server folder
    ├── rclone-filter.txt           <-- Cloud sync filter rules
    ├── config.example.json         <-- Cluster configuration template
    ├── run_server.bat              <-- Host startup script (Windows)
    ├── run_server.sh               <-- Host startup script (Linux/macOS)
    ├── sync_pull.bat / .sh         <-- Manual inbound sync script
    └── sync_push.bat / .sh         <-- Manual outbound sync script

minecraft-server/                   <-- [SERVER RUNTIME - EXCLUDED FROM GIT]
├── config.json                     <-- Local credentials (b2_bucket, key_id, application_key)
├── rclone-filter.txt               <-- Excludes local binaries, cache, logs, and credentials
├── run_server.bat / .sh            <-- Main launcher
├── sync_pull.bat / .sh             <-- Pulls newest world from Backblaze B2
├── sync_push.bat / .sh             <-- Pushes world to Backblaze B2 (with generation check)
├── ServerLauncher.jar              <-- Built orchestrator binary
├── bin/
│   ├── core.dat                    <-- Paper server JAR renamed to core.dat
│   └── rclone.exe                  <-- Rclone standalone binary (Windows)
├── plugins/
│   ├── ClusterGuard.jar            <-- Safe-launch protection plugin
│   └── playit-minecraft-plugin.jar <-- High-speed Anycast tunnel plugin
└── world/                          <-- Game save files (Overworld, Nether, End)
```

---

## Setup Instructions for the Cluster Admin

### 1. Prerequisites (Cross-Platform)
- **Java 21:** [Adoptium Temurin 21](https://adoptium.net/temurin/releases/?version=21) (Windows, macOS, or Linux).
- **Rclone:**
  - **Windows:** Download [rclone.exe](https://downloads.rclone.org/rclone-current-windows-amd64.zip) into `minecraft-server/bin/rclone.exe`.
  - **Linux / Raspberry Pi:** `curl https://rclone.org/install.sh | sudo bash`
  - **macOS:** `brew install rclone`
- A free **Backblaze B2** account (10 GB free tier).

### 2. Backblaze B2 Bucket Setup
1. Create a **Private** bucket (e.g. `krege-B-minecraft-server`) with Object Lock disabled.
2. Go to **Application Keys $\rightarrow$ Add a New Application Key**:
   - Name: `mc-cluster-sync`
   - Bucket: Restrict to your Minecraft bucket.
   - Access: **Read and Write**.
3. Save your `keyID` and `applicationKey`.

### 3. Build & Deploy
From `mc-cluster-orchestrator`:
- **Windows:** Run `build.bat`
- **Linux / macOS:** Run `./build.sh`

### 4. Configure `minecraft-server/config.json`
Fill in your Backblaze credentials:
```json
{
  "server_jar": "bin/core.dat",
  "java_memory": "6G",
  "min_ram": "2G",
  "max_ram": "6G",
  "heartbeat_seconds": 120,
  "stale_timeout_minutes": 10,
  "save_interval_minutes": 15,
  "b2_bucket": "YOUR_BUCKET_NAME",
  "b2_key_id": "YOUR_APPLICATION_KEY_ID",
  "b2_application_key": "YOUR_APPLICATION_KEY"
}
```

### 5. Push the World to the Cloud
Seed the cloud with your current world and server settings:
- **Windows:** Double-click `minecraft-server/sync_push.bat` (or run `java -jar minecraft-server/ServerLauncher.jar push`).
- **Linux / macOS:** Run `./minecraft-server/sync_push.sh`.

### 6. Package the Co-Host Starter Pack
Generate a ready-to-send, pre-configured zip file for your co-hosts:
- **Windows:** Double-click `create_cohost_pack.bat` in the repository root.
- **Linux / macOS:** Run `./create_cohost_pack.sh`.

This creates **`cohost-starter-pack.zip`** (~80 MB) containing all binaries, scripts, and pre-configured keys. Send this zip to your co-hosts (via Discord, Google Drive, etc.).

---

## Setup Instructions for Co-Hosts

Co-hosts **do not** need Git, do not need to register for Backblaze, and do not compile any code. Everything is pre-assembled for them.

1. **Extract the Starter Pack:** Extract the `cohost-starter-pack.zip` sent by the Cluster Admin to any folder (e.g. `C:\minecraft-server\` or `~/minecraft-server`).
2. **Install Java 21:** [Adoptium Temurin 21](https://adoptium.net/temurin/releases/?version=21) (Windows, macOS, or Linux).
3. **Run the Server:**
   - **Windows:** Double-click **`run_server.bat`**.
   - **Linux / macOS:** Run `chmod +x *.sh && ./run_server.sh`.
   - *Everything (`config.json` with cloud keys, `rclone.exe`, Paper core, and Playit tunnel credentials) is already configured.*
   - On the first launch, the orchestrator connects to Backblaze B2, downloads the newest world, verifies generation, and starts hosting!

---

## Operating the Cluster

### Starting the Server
Run `run_server.bat` (Windows) or `./run_server.sh` (Linux/macOS):
1. Verifies port `25565` is available.
2. Checks cloud lock in Backblaze B2. If another co-host is active, it aborts.
3. Acquires cloud lock.
4. Pulls latest world changes and synchronizes generation sequence.
5. Launches Paper server with live keyboard forwarding.
6. Auto-saves and pushes incremental chunk snapshots every 15 minutes.

### Stopping the Server (3 Ways)

#### 1. Local Host Console
In the server terminal window, the running host can simply type:
```text
stop
```
The orchestrator intercepts the command, cleanly flushes Paper to disk, uploads the final state with `--update`, increments the Generation Number, and releases the cloud lock.

#### 2. In-Game OP Command
Any operator playing on the server can type:
```text
/stop
```
The server will save all data, notify players, and trigger the orchestrator's cloud upload and unlock routine automatically.

#### 3. Admin Remote Stop (Zero-Friction Takeover)
If an admin needs to stop a co-host's running server remotely without opening Minecraft:
- **Windows:** Double-click **`admin_remote_stop.bat`** in the repository root.
- **Linux / macOS:** Run **`./admin_remote_stop.sh`**.

**How it works:**
1. The script prompts for an optional reason (e.g. *"Admin taking over hosting"*).
2. It writes a `remote_stop.signal` into Backblaze B2.
3. Within 30 seconds, the running host's launcher detects the signal.
4. It broadcasts a polite countdown to online players:
   ```text
   [CLUSTER] Remote server stop requested by Admin.
   [CLUSTER] Reason: Admin taking over hosting
   [CLUSTER] Server will save and shut down in 30 seconds...
   ```
5. It flushes all chunks (`save-all flush`), stops Paper, syncs the final world state to Backblaze B2, and releases the cluster lock. Zero player progress is lost.

### Packaging & Distributing to Co-Hosts
As the admin, you can generate a complete, 1-click starter zip for your co-hosts anytime:
- **Windows:** Double-click `create_cohost_pack.bat`.
- **Linux / macOS:** Run `./create_cohost_pack.sh`.

This generates `cohost-starter-pack.zip` (~80 MB) at the repository root. Send this zip to your co-hosts (via Discord, Google Drive, etc.). It includes the pre-configured `config.json`, `rclone.exe`, server core, Playit tunnel credentials, and launch scripts. When they unzip it and click `run_server.bat`, it downloads the latest world from Backblaze B2 and boots immediately.

### Manual Sync Scripts
- **`sync_pull.bat` / `.sh`:** Pulls the newest world without starting the server. (Blocked if another host is active).
- **`sync_push.bat` / `.sh`:** Pushes local changes to the cloud. (Blocked if cloud has newer generation or is locked).
- **`force_unlock.bat` / `.sh`:** Admin tool to clear stuck cloud and local locks if a host machine crashes.
