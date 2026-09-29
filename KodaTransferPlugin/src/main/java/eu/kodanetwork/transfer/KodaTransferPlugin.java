package eu.kodanetwork.transfer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Enables the Paper Transfer API for KodaHosting servers.
 *
 * Two jobs:
 *   - keeps {@code accepts-transfers=true} in server.properties, which allows players to be sent
 *     to this server from the lobby network,
 *   - provides {@code /khub}, which sends a player back to the KodaHosting lobby.
 *
 * The lobby address is configurable in config.yml (lobby-host, lobby-port).
 */
public class KodaTransferPlugin extends JavaPlugin implements CommandExecutor {

    private String lobbyHost = "lobby.kodanetwork.eu";
    private int lobbyPort = 25565;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lobbyHost = getConfig().getString("lobby-host", lobbyHost);
        lobbyPort = getConfig().getInt("lobby-port", lobbyPort);

        PluginCommand command = getCommand("khub");
        if (command != null) {
            command.setExecutor(this);
        }

        ensureTransfersEnabled();
        getLogger().info("KodaTransfer enabled (lobby: " + lobbyHost + ":" + lobbyPort + ")");
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        player.sendMessage(ChatColor.GREEN + "Sending you to the lobby...");
        Bukkit.getScheduler().runTask(this, () -> transfer(player));
        return true;
    }

    private void transfer(Player player) {
        try {
            player.transfer(lobbyHost, lobbyPort);
        } catch (Throwable t) {
            player.sendMessage(ChatColor.RED + "Could not reach the lobby: " + t.getMessage());
            getLogger().warning("Transfer failed for " + player.getName() + ": " + t.getMessage());
        }
    }

    /**
     * Ensures {@code accepts-transfers=true} in server.properties. The app writes this value as
     * well, this is the safety net for servers that were set up before the flag existed.
     */
    private void ensureTransfersEnabled() {
        File file = new File(".", "server.properties");
        if (!file.exists()) return;
        List<String> lines = new ArrayList<>();
        boolean found = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("accepts-transfers=")) {
                    lines.add("accepts-transfers=true");
                    found = true;
                } else {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            getLogger().warning("Could not read server.properties: " + e.getMessage());
            return;
        }
        if (!found) {
            lines.add("accepts-transfers=true");
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            for (String line : lines) {
                writer.println(line);
            }
        } catch (IOException e) {
            getLogger().warning("Could not write server.properties: " + e.getMessage());
            return;
        }
        getLogger().info("accepts-transfers=true is set.");
    }
}
