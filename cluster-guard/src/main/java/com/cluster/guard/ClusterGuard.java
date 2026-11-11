package com.cluster.guard;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ClusterGuard Fail-Safe Plugin.
 * Enforces execution through the distributed cluster orchestrator.
 * Halts server boot immediately during STARTUP phase if the safe launch flag is absent.
 * Intercepts uncoordinated in-game/console restart and reset commands to prevent split-brain collisions.
 */
public class ClusterGuard extends JavaPlugin implements Listener {

    @Override
    public void onLoad() {
        String safeLaunch = System.getProperty("cluster.safe_launch");

        if (!"true".equalsIgnoreCase(safeLaunch)) {
            getLogger().severe("=======================================================================");
            getLogger().severe("[CLUSTER GUARD] FATAL: DIRECT SERVER LAUNCH PROHIBITED!");
            getLogger().severe("[CLUSTER GUARD] Launching the server core directly is not permitted.");
            getLogger().severe("[CLUSTER GUARD] You must execute 'LAUNCH_ORCHESTRATOR' to coordinate with");
            getLogger().severe("[CLUSTER GUARD] the distributed cluster orchestrator and release locks.");
            getLogger().severe("=======================================================================");

            Bukkit.shutdown();
            System.exit(1);
        } else {
            getLogger().info("[ClusterGuard] Safe launch flag verified. Orchestrator active.");
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("[ClusterGuard] Collision & Uncoordinated Restart Guards active.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[ClusterGuard] Shutdown initiated. Cloud synchronization will be handled by orchestrator.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerCommand(ServerCommandEvent event) {
        String cmd = event.getCommand().trim().toLowerCase();
        if (isBlockedCommand(cmd)) {
            event.setCancelled(true);
            String firstWord = cmd.split("\\s+")[0];
            getLogger().warning("=======================================================================");
            getLogger().warning("[ClusterGuard] Command '" + firstWord + "' is blocked in cluster mode!");
            getLogger().warning("[ClusterGuard] Uncoordinated restarts bypass cloud locks and corrupt saves.");
            getLogger().warning("[ClusterGuard] Please use 'stop' or the Orchestrator GUI to conclude safely.");
            getLogger().warning("=======================================================================");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim().toLowerCase();
        if (message.startsWith("/")) {
            message = message.substring(1);
        }
        if (isBlockedCommand(message)) {
            event.setCancelled(true);
            String firstWord = message.split("\\s+")[0];
            getLogger().warning("[ClusterGuard] Intercepted and blocked in-game '/" + firstWord + "' command.");
        }
    }

    private boolean isBlockedCommand(String command) {
        if (command == null || command.isEmpty()) return false;
        String lower = command.toLowerCase().trim();
        if (lower.startsWith("/")) lower = lower.substring(1);
        String firstWord = lower.split("\\s+")[0];

        return firstWord.equals("restart")
                || firstWord.equals("reset")
                || firstWord.equals("reload")
                || firstWord.equals("rl");
    }
}


