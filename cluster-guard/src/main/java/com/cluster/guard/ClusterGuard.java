package com.cluster.guard;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ClusterGuard Fail-Safe Plugin.
 * Enforces execution through the distributed cluster orchestrator (run_server.bat).
 * Halts server boot immediately during STARTUP phase if the safe launch flag is absent.
 */
public class ClusterGuard extends JavaPlugin {

    @Override
    public void onLoad() {
        String safeLaunch = System.getProperty("cluster.safe_launch");

        if (!"true".equalsIgnoreCase(safeLaunch)) {
            getLogger().severe("=======================================================================");
            getLogger().severe("[CLUSTER GUARD] FATAL: DIRECT SERVER LAUNCH PROHIBITED!");
            getLogger().severe("[CLUSTER GUARD] Launching the server core directly is not permitted.");
            getLogger().severe("[CLUSTER GUARD] You must execute 'run_server.bat' to coordinate with");
            getLogger().severe("[CLUSTER GUARD] the distributed cluster orchestrator and release locks.");
            getLogger().severe("=======================================================================");

            Bukkit.shutdown();
            System.exit(1);
        } else {
            getLogger().info("[ClusterGuard] Safe launch flag verified. Orchestrator active.");
        }
    }
}

