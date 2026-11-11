# Minecraft Cluster Orchestrator: Setup & Running Instructions

This guide provides step-by-step instructions for **Co-Hosts (Friends)** joining an existing cluster and **Administrators** setting up a brand-new cluster from scratch.

For architectural capabilities, concurrency models, and technical specifications, see [README.md](README.md).

---

## Table of Contents
1. [For Co-Hosts & Friends (Quick Start)](#1-for-co-hosts--friends-quick-start)
   * [Method A: Joining via Invite Key (mc-cluster://...)](#method-a-joining-via-invite-key-mc-cluster)
   * [Method B: Joining via Zero-Egress Starter Pack (.zip)](#method-b-joining-via-zero-egress-starter-pack-zip)
2. [For Cluster Administrators (Initial Setup)](#2-for-cluster-administrators-initial-setup)
   * [Step 1: Set Up Free Backblaze B2 Storage](#step-1-set-up-free-backblaze-b2-storage)
   * [Step 2: Run the Admin Setup Wizard](#step-2-run-the-admin-setup-wizard)
   * [Step 3: Generate & Share Invite Keys](#step-3-generate--share-invite-keys)
3. [Operational Runbook (Daily Management)](#3-operational-runbook-daily-management)
   * [Distributing Plugins, Configs & Engine Updates](#distributing-plugins-configs--engine-updates)
   * [Ordering a Graceful Remote Stop](#ordering-a-graceful-remote-stop)
   * [Releasing a Stuck Cloud Lock (Emergency)](#releasing-a-stuck-cloud-lock-emergency)
   * [Purging Dead Chunk Revisions (Freeing Storage)](#purging-dead-chunk-revisions-freeing-storage)
   * [Packaging a Zero-Egress Starter Pack](#packaging-a-zero-egress-starter-pack)
4. [Command-Line & Headless Reference](#4-command-line--headless-reference)

---

## 1. For Co-Hosts & Friends (Quick Start)

Co-hosts do **not** need a Backblaze account, do **not** configure port forwarding, and do **not** touch raw JSON configuration files.

### Step 1: Install the Launcher
Download the installer for your operating system from [GitHub Releases](https://github.com/WwFour13/mc-cluster-orchestrator/releases/latest):

* **Windows**: Download and run `MinecraftClusterSetup.exe`.
  * *Features*: Automatically configures Java 21 LTS if missing, creates Desktop and Start Menu shortcuts with the native cluster icon.
  * *Portable Alternative*: Download `Minecraft-Cluster-Windows-Portable.zip`, extract to any folder, and double-click `MinecraftCluster.exe`.
* **macOS**: Download `Minecraft-Cluster-macOS.zip`, extract, and drag `Minecraft Cluster Server.app` to your Applications or Desktop.
* **Linux**: Open terminal and run the one-line bootstrap installer:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/WwFour13/mc-cluster-orchestrator/master/install.sh | bash
  ```

---

### Method A: Joining via Invite Key (`mc-cluster://...`)
Use this method when your cluster admin sends you a cluster invite key string.

1. Launch **Minecraft Cluster Server** from your desktop or application menu.
2. On first launch, click **👥 Join a Cluster (Cohost)**.
3. Paste the `mc-cluster://...` key provided by your admin into the token field.
4. Select a local folder on your computer where the Minecraft server files will be stored.
5. Click **Connect & Initialize Cluster**.
6. On the Dashboard, click **▶ START CLUSTER SERVER**. The orchestrator will download the world, acquire the cloud lease, and launch the server!

---

### Method B: Joining via Zero-Egress Starter Pack (`.zip`)
Use this method if your admin sent you a `cohost-starterpack.zip` file (e.g. over Discord, Google Drive, or USB drive). This onboards you with **zero cloud bandwidth consumption**.

1. Launch **Minecraft Cluster Server**.
2. Click **📦 Import Starter Pack (.zip)**.
3. Browse and select the `cohost-starterpack.zip` file provided by your admin.
4. Choose an installation directory and click **Import & Register Cluster**.
5. Once extraction finishes, click **▶ START CLUSTER SERVER**. You are immediately synchronized and ready to host!

---

## 2. For Cluster Administrators (Initial Setup)

### Step 1: Set Up Free Backblaze B2 Storage
Backblaze B2 provides 10 GB of free cloud storage and 1 GB of daily free download bandwidth, making it ideal for hosting a shared Minecraft cluster with zero recurring costs.

1. Create a free account at [Backblaze B2](https://www.backblaze.com/b2/cloud-storage.html).
2. Go to **Buckets $\rightarrow$ Create a Bucket**:
   * **Bucket Unique Name**: Choose a unique identifier (e.g. `my-dorm-smp-cluster`).
   * **Files in Bucket are**: Select **Private**.
   * **Default Encryption**: Enabled (SSE-B2).
   * **Object Lock**: **Disabled**.
   * Click **Create a Bucket**.
3. **Configure Lifecycle Rules (Recommended)**:
   * In your new bucket's settings, click **Lifecycle Rules**.
   * Select **"Keep only the last version of the file"** (or custom rule: *Days from hiding to deleting = 1*).
   * *Why*: Minecraft autosaves write chunks frequently. This rule ensures older superseded revisions are cleaned up automatically.
4. **Generate Application Keys**:
   * Navigate to **Account $\rightarrow$ Application Keys $\rightarrow$ Add a New Application Key**.
   * **Key Name**: `mc-cluster-admin`
   * **Bucket**: Restrict access to your Minecraft bucket.
   * **Type of Access**: **Read and Write**.
   * Click **Create New Key**.
   * Copy and save your **`keyID`** and **`applicationKey`** (the application key is only shown once).

---

### Step 2: Run the Admin Setup Wizard
1. Launch **Minecraft Cluster Server** on your computer.
2. Click **👑 Create a Cluster (Admin)**.
3. Choose your installation method:
   * **Fresh Paper Server (Recommended)**: Let the orchestrator download, configure, and optimize the latest verified PaperMC Minecraft engine. Select your Minecraft version (e.g. 1.20.4).
   * **Adopt Existing Server**: Select an existing local Minecraft server folder on your PC. The orchestrator will verify `core.dat` and preserve your existing world, plugins, and configs.
4. Enter your **Bucket Name**, **Application Key ID**, and **Application Key**.
5. Click **Initialize Cluster**.

The orchestrator will connect to Backblaze, establish the cloud channel, and configure your local runtime.

---

### Step 3: Generate & Share Invite Keys
1. Open the **[ 🛡️ Cluster Admin ]** tab in the top navigation.
2. In the top card, click **[ COPY KEY ]** next to your cluster invite token (`mc-cluster://...`).
3. Send this key to your trusted friends/co-hosts.
4. When they paste this token into their launcher, their machine is configured automatically with zero manual setup.

---

## 3. Operational Runbook (Daily Management)

All day-to-day cluster operations can be performed directly from the **[ 🛡️ Cluster Admin ]** panel in the desktop GUI:

### Distributing Plugins, Configs & Engine Updates
Whenever you install new plugins (e.g. Dynmap, Essentials, DiscordSRV), tweak `server.properties`, modify Paper YAML files, or upgrade the orchestrator:

1. Open the **[ 🛡️ Cluster Admin ]** tab.
2. Click **`☁️ SYNC CONFIGS & PLUGINS`** (Blue Button).
3. Confirm the prompt.
4. **What Happens Behind the Scenes**:
   * Pushes the latest `ServerLauncher.jar` engine to the cloud update channel.
   * Uploads all `plugins/`, server configs, and binary definitions to `/dist`.
   * Enforces `min_orchestrator_version` in the cloud manifest.
   * All co-hosts will automatically synchronize these files and software updates prior to their next boot!

---

### Ordering a Graceful Remote Stop
If another co-host is actively hosting the server and you need them to hand over control or perform maintenance:

1. In the **[ 🛡️ Cluster Admin ]** tab, click **`⛔ REQUEST REMOTE STOP`** (Red Button).
2. Enter an optional reason (e.g., *"Admin maintenance in 5 minutes"*).
3. The active host's server will immediately:
   * Broadcast a 30-second countdown in-game to all online players.
   * Execute `save-all flush` to ensure zero lost progress.
   * Stop the Paper server process gracefully.
   * Sync the final world generation to Backblaze B2.
   * Release the distributed cloud lock.

---

### Releasing a Stuck Cloud Lock (Emergency)
If a co-host suffers a power blackout, blue screen of death, or unexpected hard crash, the cloud lock might remain marked as active.

1. The orchestrator will automatically auto-reclaim the lock after 10 minutes of stale heartbeat silence.
2. To unlock immediately, go to **[ 🛡️ Cluster Admin ]** and click **`🔓 EMERGENCY FORCE UNLOCK`** (Amber Button).
3. Confirm the prompt to safely clear both the local lock and the distributed cloud lock on Backblaze B2.

---

### Purging Dead Chunk Revisions (Freeing Storage)
When Minecraft saves world chunks, older superseded chunk versions can accumulate on Backblaze B2 over weeks of continuous hosting:

1. In **[ 🛡️ Cluster Admin ]**, click **`🧹 CLEAN CLOUD STORAGE`** (Purple Button).
2. The orchestrator triggers an immediate B2 garbage collection sweep.
3. *Safety Guarantee*: Only hidden, superseded file revisions are purged. The current active world save, player inventories, and server binaries remain 100% untouched.

---

### Packaging a Zero-Egress Starter Pack
To onboard a new co-host with a large world without consuming Backblaze download quotas:

1. In **[ 🛡️ Cluster Admin ]**, look at the **COHOST STARTER PACK** card.
2. Click **`📦 EXPORT STARTER PACK (.zip)`** (Green Button).
3. The orchestrator packages your local world save, Paper engine, plugins, configurations, and launchers into `cohost-starterpack.zip`.
4. Share this zip file with your new co-host via local network, USB, or Google Drive. They can import it via **Method B** above with zero cloud egress.

---

## 4. Command-Line & Headless Reference

For advanced users, headless Linux VPS hosting, or automated CI/CD pipelines, `ServerLauncher.jar` provides full command-line control:

| Command | Action |
| :--- | :--- |
| `java -jar ServerLauncher.jar` | Launches the graphical desktop interface (GUI mode). |
| `java -jar ServerLauncher.jar --cli` | Boots the server in headless CLI mode with console logging. |
| `java -jar ServerLauncher.jar status` | Inspects live cloud lock, active host name, world generation number, and Anycast tunnel status. |
| `java -jar ServerLauncher.jar pull` | Pulls the latest world generation from Backblaze B2 with anti-collision checks. |
| `java -jar ServerLauncher.jar push` | Flushes local world saves, increments the generation counter, and syncs to B2. |
| `java -jar ServerLauncher.jar publish-update` | Uploads plugins, runtime assets, configs, and the latest orchestrator to `/dist`. |
| `java -jar ServerLauncher.jar push-launcher` | Uploads the standalone `ServerLauncher.jar` to B2 for co-host auto-updating. |
| `java -jar ServerLauncher.jar cleanup` | Executes an immediate cloud storage sweep, purging dead historical chunk revisions. |
| `java -jar ServerLauncher.jar unlock` | Releases stranded local and distributed cloud locks. |
| `java -jar ServerLauncher.jar remote-stop [reason]` | Dispatches a remote stop signal to the active host. |
| `java -jar ServerLauncher.jar export-starterpack [path]` | Exports an offline `cohost-starterpack.zip`. |
| `java -jar ServerLauncher.jar import-starterpack <zip> [dir]` | Imports an offline starter pack into a new cluster workspace. |

---

## Need Further Help?
* **Bug Reports & Issues**: [GitHub Issues](https://github.com/WwFour13/mc-cluster-orchestrator/issues)
* **Architecture & Theory**: See [README.md](README.md)
