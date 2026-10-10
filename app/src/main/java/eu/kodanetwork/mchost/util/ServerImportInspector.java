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
package eu.kodanetwork.mchost.util;

import eu.kodanetwork.mchost.model.ServerInstance;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * looks at a server folder (an imported backup for example) and works out what is in
 * there: which jars could be the launcher, which server software and minecraft version
 * it looks like, and what the folder contains that the app should know about.
 *
 * everything here is a guess based on names and a few known files, so the caller shows
 * the result to the user instead of trusting it blindly.
 */
public class ServerImportInspector {

    /** one jar that could be the file to launch. */
    public static class Candidate {
        public final File file;
        public final String name;
        public final ServerInstance.Type type;   // null when the name says nothing
        public final String version;             // minecraft version, "" when unknown
        public final String note;                // short hint for the picker

        Candidate(File file, ServerInstance.Type type, String version, String note) {
            this.file = file;
            this.name = file.getName();
            this.type = type;
            this.version = version == null ? "" : version;
            this.note = note == null ? "" : note;
        }
    }

    public static class Inspection {
        public final List<Candidate> candidates = new ArrayList<>();   // launcher candidates, best first
        public final List<String> installers = new ArrayList<>();      // installer jars, not launchable directly
        public ServerInstance.Type detectedType = null;
        public String detectedVersion = "";
        public final List<String> worldFolders = new ArrayList<>();
        public String levelName = "";                // from server.properties
        public String motd = "";
        public int playerLimit = 0;
        public int fileCount = 0;
        public int modCount = 0;
        public int pluginCount = 0;
        public boolean hasServerProperties = false;
        public boolean eulaAccepted = false;
        public boolean hasForgeLibraries = false;
        public boolean hasFabricLauncher = false;
        public final List<String> warnings = new ArrayList<>();

        /** the candidate to preselect: the best guess, or null when nothing looks launchable. */
        public Candidate best() {
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    private static final Pattern MC_VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    public static Inspection inspect(File dir) {
        Inspection in = new Inspection();
        if (dir == null || !dir.isDirectory()) {
            in.warnings.add("no folder to inspect");
            return in;
        }

        for (File f : dir.listFiles() != null ? dir.listFiles() : new File[0]) {
            String lower = f.getName().toLowerCase();
            if (f.isDirectory()) {
                if (new File(f, "level.dat").isFile()) in.worldFolders.add(f.getName());
                if (lower.equals("mods")) in.modCount = countFiles(f);
                if (lower.equals("plugins")) in.pluginCount = countFiles(f);
                continue;
            }
            in.fileCount++;
            if (lower.endsWith(".jar")) {
                if (lower.contains("installer")) {
                    in.installers.add(f.getName());
                    continue;
                }
                Candidate c = classify(f);
                if (c != null) in.candidates.add(c);
            }
        }

        // the fabric launcher config names the vanilla jar it wraps
        File fabricProps = new File(dir, "fabric-server-launcher.properties");
        if (fabricProps.isFile()) {
            in.hasFabricLauncher = true;
            String serverJar = readProperty(fabricProps, "serverJar");
            if (!serverJar.isEmpty()) {
                File vanilla = new File(dir, serverJar);
                if (vanilla.isFile() && !containsCandidate(in, vanilla.getName())) {
                    in.candidates.add(new Candidate(vanilla, ServerInstance.Type.FABRIC, "", "wrapped by fabric"));
                }
            }
        }

        // libraries tell the real story for forge
        File forgeLib = new File(dir, "libraries/net/minecraftforge/forge");
        if (forgeLib.isDirectory()) {
            in.hasForgeLibraries = true;
            String mc = versionFromForgeLib(forgeLib);
            if (!mc.isEmpty()) in.detectedVersion = mc;
        }
        File neoLib = new File(dir, "libraries/net/neoforged/neoforge");
        if (neoLib.isDirectory() && neoLib.isDirectory()) {
            String mc = versionFromNeoForgeLib(neoLib);
            if (!mc.isEmpty()) in.detectedVersion = mc;
        }

        // version.json is the one file every flavour ships and it names the minecraft version
        File versionJson = new File(dir, "version.json");
        if (versionJson.isFile()) {
            String v = readVersionJson(versionJson);
            if (!v.isEmpty()) in.detectedVersion = v;
        }

        File props = new File(dir, "server.properties");
        if (props.isFile()) {
            in.hasServerProperties = true;
            in.levelName = readProperty(props, "level-name");
            in.motd = readProperty(props, "motd");
            try {
                in.playerLimit = Integer.parseInt(readProperty(props, "max-players").trim());
            } catch (Exception ignored) {}
        }
        File eula = new File(dir, "eula.txt");
        if (eula.isFile()) in.eulaAccepted = readProperty(eula, "eula").equalsIgnoreCase("true");

        // what did we end up with
        Candidate best = in.best();
        if (best != null) {
            if (in.detectedType == null && best.type != null) in.detectedType = best.type;
            if (in.detectedVersion.isEmpty()) in.detectedVersion = best.version;
        }
        if (in.detectedType == null) {
            if (in.hasForgeLibraries) in.detectedType = in.pluginCount > 0 ? ServerInstance.Type.PAPER : ServerInstance.Type.FORGE;
            else if (in.hasFabricLauncher) in.detectedType = ServerInstance.Type.FABRIC;
        }

        // and what the user should know before jumping in
        if (best == null) {
            in.warnings.add(in.installers.isEmpty()
                    ? "no server jar in this folder"
                    : "only an installer jar, the server has to be installed first");
        }
        if (in.worldFolders.isEmpty()) {
            in.warnings.add("no world folder in here");
        } else if (in.worldFolders.size() > 1) {
            in.warnings.add(in.worldFolders.size() + " worlds found, the server will use one of them");
        }
        if (in.worldFolders.size() == 1 && !in.levelName.isEmpty() && !in.levelName.equals(in.worldFolders.get(0))) {
            in.warnings.add("server.properties points at " + in.levelName + " but the folder holds " + in.worldFolders.get(0));
        }
        if (!in.hasServerProperties) in.warnings.add("no server.properties, the server writes a fresh one");
        if (!in.eulaAccepted) in.warnings.add("EULA is not accepted yet");
        return in;
    }

    /** writes the detection result onto the server, keeping what the user picked. */
    public static void apply(ServerInstance s, Inspection in, Candidate chosen) {
        Candidate c = chosen != null ? chosen : in.best();
        if (c != null) {
            if (c.type != null) s.setType(c.type);
            if (!c.version.isEmpty()) s.setVersion(c.version);
            else if (!in.detectedVersion.isEmpty()) s.setVersion(in.detectedVersion);
            s.setLauncherJar(c.name);
        } else if (in.detectedType != null) {
            s.setType(in.detectedType);
            if (!in.detectedVersion.isEmpty()) s.setVersion(in.detectedVersion);
        }
    }

    /** name based guess for one jar. */
    private static Candidate classify(File jar) {
        ServerInstance.Type type = typeForJarName(jar.getName());
        return type == null ? null : new Candidate(jar, type, versionForJarName(jar.getName()), noteForJarName(jar.getName()));
    }

    /**
     * which software a jar name points at, null when the name says nothing.
     * shared with the create screen, which sees the same names while reading a ZIP.
     */
    public static ServerInstance.Type typeForJarName(String fileName) {
        String n = fileName.toLowerCase();
        if (n.contains("installer")) return null;
        if (n.startsWith("paper-"))    return ServerInstance.Type.PAPER;
        if (n.startsWith("purpur-"))   return ServerInstance.Type.PURPUR;
        if (n.startsWith("folia-"))    return ServerInstance.Type.FOLIA;
        if (n.startsWith("spigot-"))   return ServerInstance.Type.PAPER;
        if (n.startsWith("fabric-server")) return ServerInstance.Type.FABRIC;
        if (n.startsWith("neoforge-")) return ServerInstance.Type.NEOFORGE;
        if (n.startsWith("forge-"))    return ServerInstance.Type.FORGE;
        if (n.startsWith("minecraft_server.")) return ServerInstance.Type.VANILLA;
        if (n.equals("server.jar") || n.startsWith("server-")) return ServerInstance.Type.VANILLA;
        if (n.contains("velocity"))    return ServerInstance.Type.VELOCITY;
        return null;   // a random jar, probably a mod that lies around
    }

    /** the minecraft version inside a jar name, "" when the name carries none. */
    public static String versionForJarName(String fileName) {
        String n = fileName.toLowerCase();
        String found = firstVersion(n);
        if (typeForJarName(fileName) == ServerInstance.Type.NEOFORGE) {
            return minecraftFromNeoForge(found);
        }
        if (n.startsWith("server-")) return "";   // paper builds like server-1.21.4-232.jar are handled elsewhere
        return (n.startsWith("server") && !n.startsWith("minecraft_server.")) ? "" : found;
    }

    /** the short line the pickers show under a name. */
    public static String noteForJarName(String fileName) {
        String n = fileName.toLowerCase();
        if (n.startsWith("spigot-")) return "spigot, runs like paper";
        if (n.startsWith("minecraft_server.")) return "vanilla";
        if (n.startsWith("fabric-server")) return "fabric";
        if (n.startsWith("neoforge-")) return "neoforge";
        if (n.startsWith("forge-")) return "forge";
        if (n.contains("velocity")) return "velocity proxy";
        if (n.equals("server.jar") || n.startsWith("server-")) return "plain server jar";
        return "";
    }

    private static String firstVersion(String lowerName) {
        Matcher m = MC_VERSION.matcher(lowerName);
        return m.find() ? m.group(1) : "";
    }

    /** neoforge versions start with the minecraft minor and patch, 21.1.72 is minecraft 1.21.1. */
    private static String minecraftFromNeoForge(String neoVersion) {
        if (neoVersion.isEmpty()) return "";
        String[] parts = neoVersion.split("\\.");
        if (parts.length < 2) return "";
        return "1." + parts[0] + (parts[1].equals("0") ? "" : "." + parts[1]);
    }

    private static String versionFromForgeLib(File forgeLib) {
        File[] versions = forgeLib.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) return "";
        String latest = versions[versions.length - 1].getName();   // "1.12.2-14.23.5.2860"
        int dash = latest.indexOf('-');
        return dash > 0 ? latest.substring(0, dash) : "";
    }

    private static String versionFromNeoForgeLib(File neoLib) {
        File[] versions = neoLib.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) return "";
        return minecraftFromNeoForge(versions[versions.length - 1].getName());
    }

    private static String readVersionJson(File file) {
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
                if (sb.length() > 4000) break;
            }
            Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(sb.toString());
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String readProperty(File file, String key) {
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || !line.contains("=")) continue;
                int eq = line.indexOf('=');
                if (line.substring(0, eq).trim().equalsIgnoreCase(key)) {
                    return line.substring(eq + 1).trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static int countFiles(File dir) {
        File[] files = dir.listFiles();
        return files == null ? 0 : files.length;
    }

    private static boolean containsCandidate(Inspection in, String name) {
        for (Candidate c : in.candidates) if (c.name.equals(name)) return true;
        return false;
    }
}
