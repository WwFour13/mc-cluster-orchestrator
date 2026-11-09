# Minecraft Cluster Orchestrator (v2.0)

[![Release](https://img.shields.io/badge/Release-v2.0.0-emerald.svg)](https://github.com/WwFour13/mc-cluster-orchestrator/releases)
[![Java](https://img.shields.io/badge/Java-21%20LTS-blue.svg)](https://adoptium.net/temurin/releases/?version=21)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20macOS%20%7C%20Linux-orange.svg)]()
[![Cloud Sync](https://img.shields.io/badge/Cloud%20Sync-Backblaze%20B2%20OCC-purple.svg)]()
[![Tunneling](https://img.shields.io/badge/Tunneling-Playit.gg%20Anycast-red.svg)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)]()

A distributed, fail-safe Minecraft server orchestrator. Coordinates 24/7 multiplayer hosting across multiple friends' PCs with zero server rental costs, zero player VPNs (no Hamachi or port forwarding required), and optimistic concurrency cloud synchronization using a free Backblaze B2 bucket.

---

## What's New in v2.0 (The Visual Era)

* **Modern Dark-Themed GUI:** Completely replaces terminal scripts with a native desktop interface featuring an animated 5-milestone ignition pipeline, system RAM allocation slider, live server console, and command dispatcher.
* **Zero-Config Invite Keys (`mc-cluster://...`):** Friends never touch JSON files. Pasting an invite key automatically decodes cloud endpoints, security credentials, and storage paths.
* **Universal One-Click Installers:**
  * **Windows:** Standalone native installer (`MinecraftClusterSetup.exe`) that auto-detects or installs Java 21 LTS via Winget, extracts runtime assets, and creates Desktop and Start Menu search shortcuts.
  * **macOS:** Self-contained drag-and-drop `Minecraft Cluster Server.app` bundle.
  * **Linux:** 1-line curl bootstrap script with automatic package manager detection (`apt`, `dnf`, `pacman`, `zypper`).
* **Dynamic Anycast Tunnel Routing:** Sniffs and resolves live PlayIt Anycast tunnel addresses (e.g. `jensen-edited.tun.ply.gg`) directly from runtime logs with a one-click **[ COPY IP ]** button for players.
* **In-GUI Operational Controls:**
  * **`⛔ REQUEST REMOTE STOP`**: Broadcasts a polite 30-second countdown in-game, safely saves the world, and uploads changes to the cloud before transferring host control.
  * **`🔓 EMERGENCY FORCE UNLOCK`**: Safely clears stranded cloud locks if a co-host loses power or suffers a hardware crash.
  * **`☁️ SYNC CONFIGS & PLUGINS`**: 1-click cloud synchronization of plugins, `server.properties`, and YAML configs across all co-hosts without touching command-line utilities.
* **Anti-Downgrade Version Guard:** Enforces `min_orchestrator_version` in cloud manifests to protect distributed saves from being corrupted by outdated launcher builds.

---

## Quick Start: For Co-Hosts & Friends (30 Seconds)

Co-hosts do not need Git, do not need to register for Backblaze, and do not compile code.

### 1. Download the Installer
Download the package for your operating system from [GitHub Releases](https://github.com/WwFour13/mc-cluster-orchestrator/releases/latest):
* **Windows:** Download and run `MinecraftClusterSetup.exe`.
* **macOS:** Download `Minecraft-Cluster-macOS.zip`, unzip, and drag `Minecraft Cluster Server.app` to your Applications or Desktop.
* **Linux:** Run the bootstrap command in your terminal:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/WwFour13/mc-cluster-orchestrator/master/install.sh | bash
  ```

### 2. Connect to the Cluster
1. Launch the **Minecraft Cluster Server** application.
2. On first launch, click **👥 Join a Cluster (Cohost)**.
3. Paste the `mc-cluster://...` invite key provided by your cluster admin.
4. Select where on your drive to store server files and click **Connect & Initialize Cluster**.

### 3. Start Playing
Click **▶ START CLUSTER SERVER**. The orchestrator will verify port availability, acquire the distributed cloud lock, synchronize the latest world generation, and ignite the server!

---

## Quick Start: For Cluster Administrators (Initial Setup)

### 1. Set Up Backblaze B2 (Free Tier)
1. Create a free account at [Backblaze](https://www.backblaze.com/b2/cloud-storage.html) (10 GB free storage, 1 GB daily free egress).
2. Create a **Private** bucket (e.g. `my-minecraft-cluster`) with Object Lock disabled.
3. Go to **Application Keys $\rightarrow$ Add a New Application Key**:
   * **Key Name:** `mc-cluster-sync`
   * **Bucket:** Restrict to your Minecraft bucket.
   * **Access:** **Read and Write**.
4. Note your `keyID` and `applicationKey`.

### 2. Launch the Admin Wizard
1. Run `MinecraftClusterSetup.exe` (or your platform's launcher).
2. Click **👑 Create a Cluster (Admin)**.
3. Choose your setup mode:
   * **Adopt Existing Server:** Select an existing local Minecraft Paper server directory.
   * **Fresh Paper Server:** Let the orchestrator download and configure the latest verified PaperMC engine.
4. Enter your Backblaze B2 bucket name, Key ID, and Application Key.
5. Click **Initialize Cluster**.

### 3. Share the Invite Key
1. Switch to the **[ 🛡️ Cluster Admin ]** tab in the top navigation.
2. Click **COPY KEY** next to your cluster invite token (`mc-cluster://...`).
3. Send this key to your trusted co-hosts.

### 4. Distributing Plugins & Server Tweaks
Whenever you or an authorized co-host install a new plugin (e.g. Dynmap, Essentials) or update `server.properties`:
1. Navigate to the **[ 🛡️ Cluster Admin ]** tab.
2. Click **`☁️ SYNC CONFIGS & PLUGINS`**.
3. Confirm the prompt. The orchestrator packages and uploads all plugins, configs, and engine binaries to the cloud distribution channel. All co-hosts automatically receive these assets prior to their next boot.

---

## Graphical Interface & Controls

```
┌────────────────────────────────────────────────────────────────────────┐
│ MINECRAFT CLUSTER v2.0.0      [ Dashboard ]  [ Cluster Admin ]  [ Settings ]│
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│ Dorm Craft SMP — Shared Cluster                       ● Active on MSI  │
│ Host: WwFour-845-G7 | Storage: C:\dev\minecraft-server      ⟳ Refresh │
│                                                                        │
│ ┌──────────────────────┐  Memory Allocation: 8 GB                      │
│ │ ▶ START CLUSTER SERVER│  [=====|============== 31 GB Total]           │
│ └──────────────────────┘  Connection: jensen-edited.tun.ply.gg [COPY]  │
│                                                                        │
│ 1. Up to Date   2. Lock Acquired   3. Synchronized   4. Ready   5. LIVE │
│ └──────────────────────────────────────────────────────────────────────┘
│                                                                        │
│ Live Server Console & Command Stream                                   │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │ [12:47:10] [Server thread/INFO]: Preparing spawn area: 84%         │ │
│ │ [12:47:11] [Server thread/INFO]: Done (4.218s)! For help, type ... │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ Console: [ Type command (e.g. op Player, whitelist add Player)...   ]  │
└────────────────────────────────────────────────────────────────────────┘
```

### 1. Dashboard Tab
* **Cloud Radar Badge:** Displays live cluster occupancy (`Cluster Available`, `Active on <Host>`, or `Hosting Session (Local)`).
* **Connection Address:** Sniffs live Anycast PlayIt tunnels (or DuckDNS domains) with a one-click copy button.
* **RAM Allocation Slider:** Smoothly adjusts server memory dynamically bounded by physical system limits.
* **Milestone Progress Bar:** Tracks the 5 pre-flight ignition milestones in real time.
* **Live Server Terminal:** Streams Paper engine output with interactive command entry and auto-scrolling.

### 2. Cluster Admin Tab
* **Cohost Access & Invite Key:** Displays the cluster token with single-click copy and instant refresh capabilities.
* **Operational Controls:**
  * **`⛔ REQUEST REMOTE STOP`**: Broadcasts a 30-second countdown in-game and initiates a graceful remote shutdown.
  * **`🔓 EMERGENCY FORCE UNLOCK`**: Wipes stuck local and cloud locks if an active host loses power or suffers a hardware fault.
  * **`☁️ SYNC CONFIGS & PLUGINS`**: Pushes local `plugins/`, `server.properties`, and server YAML definitions to the cluster cloud.
* **World State & Shortcuts:** Displays active generation sequence number, cloud provider status, and one-click folder shortcuts.

### 3. Settings Tab
* Adjust local storage directories, update Backblaze B2 credentials, change heartbeat intervals (default: 120s), customize autosave timing (default: 15m), and apply bandwidth throttling caps.

---

## Concurrency & Safety Guardrails

| Safeguard | How It Works |
| :--- | :--- |
| **Distributed Cloud Lock** | Coordinates the active host via `cluster.lock` in Backblaze B2. If a co-host attempts to start while another machine is hosting, launch safely aborts in-GUI. |
| **Optimistic Concurrency (OCC)** | Tracks sequential generation numbers (`manifest.json`). Local states must match or advance cloud generations, physically blocking split-brain rollbacks. |
| **Heartbeat & Stale Reclaim** | The active host updates the lock lease every 120 seconds. If a machine crashes or suffers a blackout, the lock auto-expires after 10 minutes, freeing the cluster. |
| **In-Game Incremental Push** | Every 15 minutes, Paper runs `save-all flush` and uploads modified region files (`.mca`) in the background. If a host's computer dies, at most 15 minutes of progress is lost. |
| **Anti-Downgrade Version Guard** | Every cloud sync stamps `min_orchestrator_version`. Outdated launcher builds are blocked from hosting newer saves until updated. |
| **Fail-Safe Rollback (30-Day Versioning)** | Backblaze B2 natively keeps previous revisions of chunk files for 30 days for free, making accidental griefing or corruptions immediately reversible. |
| **ClusterGuard Plugin** | Custom Paper fail-safe plugin that detects and halts direct, un-orchestrated JAR invocations. |

---

## Repository Structure

```text
mc-cluster-orchestrator/
├── pom.xml
├── README.md
├── src/
│   ├── ClusterGUI.java             <-- Modern 3-tab graphical desktop application
│   ├── ServerLauncher.java         <-- Core cloud synchronization engine & OCC coordinator
│   ├── ClusterToken.java           <-- Invite key encoder / decoder (mc-cluster://)
│   ├── GitHubUpdateChecker.java    <-- Self-updating release poller and downloader
│   └── PaperDownloader.java        <-- PaperMC automated binary fetcher
├── cluster-guard/                  <-- ClusterGuard fail-safe Paper plugin source
├── windows/
│   ├── build.bat                   <-- Local Java compiler & asset packager
│   ├── package_release.bat         <-- Release packager launcher
│   ├── package_release_assets.ps1  <-- Assembles all 4 platform release assets in out/release/
│   ├── publish_update.bat          <-- Pushes runtime assets to private B2 bucket
│   └── installer/
│       └── Installer.cs            <-- C# WinForms native installer (MinecraftClusterSetup.exe)
├── mac-linux/
│   ├── build.sh                    <-- Linux/macOS build script
│   └── publish_update.sh           <-- Cloud publish helper
├── templates/                      <-- Operational templates deployed to server runtimes
│   ├── icon.ico                    <-- Desktop and launcher icon
│   ├── rclone-filter.txt           <-- Session sync filter (world and player data)
│   ├── admin-dist-filter.txt       <-- Administrative distribution filter (plugins and configs)
│   ├── linux/install.sh            <-- 1-line Linux curl installer
│   ├── mac-linux/                  <-- macOS templates (Setup_Mac_App.command, launchers)
│   └── windows/                    <-- Windows templates (START_SERVER.bat, shortcuts)
└── out/
    └── release/                    <-- Built production release assets
        ├── MinecraftClusterSetup.exe
        ├── Minecraft-Cluster-macOS.zip
        ├── install.sh
        └── ServerLauncher.jar
```

---

## Building from Source

### Prerequisites
* **Java 21 LTS** JDK (Temurin 21 recommended)
* **Windows (Optional for `.exe` compilation):** .NET Framework 4.8 / `csc.exe` (included natively in Windows)

### Build Commands
From the repository root:
* **Windows:**
  ```cmd
  windows\build.bat
  ```
* **macOS / Linux:**
  ```bash
  chmod +x mac-linux/build.sh
  ./mac-linux/build.sh
  ```

### Packaging Release Assets
To compile the standalone Windows `.exe` installer, macOS zip, Linux script, and universal JAR:
```cmd
windows\package_release.bat
```
All four output assets will be generated in `out\release\`, ready to drag and drop into a new GitHub release tag.

---

## License
Distributed under the MIT License. See `LICENSE` for more information.
