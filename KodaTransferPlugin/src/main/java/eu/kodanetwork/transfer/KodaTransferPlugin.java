/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
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
 * turns the paper transfer API on for KodaHosting servers.
 *
 * two jobs:
 *   - keeps {@code accepts-transfers=true} in server.properties, that is what lets the lobby
 *     network send players over here,
 *   - provides {@code /khub}, which sends a player back to the KodaHosting lobby.
 *
 * the lobby address is configurable in config.yml (lobby-host, lobby-port).
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
     * forces {@code accepts-transfers=true} into server.properties. the app writes that value
     * too, so this is the safety net for servers set up before the flag existed.
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
