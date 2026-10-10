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
package eu.kodanetwork.mchost.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.kodanetwork.mchost.App;
import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.service.KodaServerService;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.ThemeHelper;

/**
 * what the owner stares at while the world is being pre-generated: the console tail,
 * chunky's progress in percent and a rough time left.
 *
 * the percentage comes out of chunky's own console output. when it only reports a chunk
 * count, the total from the chosen radius is used to turn that into a percentage.
 */
public class PregenerationActivity extends Activity {

    public static final String EXTRA_SERVER_ID = "serverId";

    private static final Pattern PERCENT = Pattern.compile("([\\d.]+)\\s*%");
    private static final Pattern CHUNKS = Pattern.compile("([\\d,.]+)\\s*chunks?", Pattern.CASE_INSENSITIVE);
    private static final long PROGRESS_POLL_MS = 20_000L;
    private static final int LOG_LINES = 60;

    private ServerInstance server;
    private ServerRepo repo;
    private KodaServerService svc;
    private boolean bound = false;
    private KodaServerService.LogCallback logCb;
    private KodaServerService.StateCallback stateCb;

    private TextView tvState, tvPercent, tvEta, tvLog, tvHead, tvStats;
    private ProgressBar progressBar;
    private WorldGenView worldGen;
    private com.airbnb.lottie.LottieAnimationView lottieWait;
    private ScrollView logScroll;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final StringBuilder logText = new StringBuilder();

    /** the progress numbers behind the estimate. */
    private double lastPercent = -1;
    private long lastPercentAt = 0;
    private double percentPerHour = 0;
    private long doneChunks = 0;
    /** true once a reading below 100% arrived, so a lone 100% right after boot is ignored. */
    private boolean sawBelow100 = false;
    private double chunksPerHour = 0;
    /** true once the world was fully generated, so the celebration runs only once. */
    private boolean finishedAnnounced = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((KodaServerService.LocalBinder) b).get();
            bound = true;
            logCb = (id, line) -> {
                if (server != null && id.equals(server.getId())) runOnUiThread(() -> onLogLine(line));
            };
            stateCb = (id, s) -> {
                if (server != null && id.equals(server.getId())) runOnUiThread(PregenerationActivity.this::refreshState);
            };
            svc.addLogCb(logCb);
            svc.addStateCb(stateCb);
            for (String line : svc.getLog(server.getId())) onLogLine(line);
            refreshState();
            h.postDelayed(pollProgress, PROGRESS_POLL_MS);
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; svc = null; }
    };

    /**
     * where KodaTransfer keeps its map. the bukkit plugin lives under plugins/, the mods
     * use a neutral folder, so modded servers are asked in their own place.
     */
    private java.io.File mapDir() {
        boolean pluginServer = eu.kodanetwork.mchost.util.ModrinthHelper.isPluginServer(server);
        return pluginServer
                ? new java.io.File(server.getServerDir(), "plugins/KodaTransfer/map")
                : new java.io.File(server.getServerDir(), "koda_transfer/map");
    }

    /** the same folder on the other side, so a map written by the other build is still found. */
    private java.io.File otherMapDir() {
        boolean pluginServer = eu.kodanetwork.mchost.util.ModrinthHelper.isPluginServer(server);
        return pluginServer
                ? new java.io.File(server.getServerDir(), "koda_transfer/map")
                : new java.io.File(server.getServerDir(), "plugins/KodaTransfer/map");
    }

    /** tells the plugin which radius to sample, and that this screen wants a map at all. */
    private void writeMapRequest() {
        try {
            java.io.File dir = mapDir();
            dir.mkdirs();
            int radius = App.getPrefs(this).getInt("pregenerate_radius", 1000);
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "map_request.json"))) {
                w.write("{\"radius\":" + radius + ",\"cells\":96,\"enabled\":true}");
            }
        } catch (Exception ignored) {}
    }

    /** asks for the newest picture while the screen is open. */
    private final Runnable mapPoll = new Runnable() {
        @Override public void run() {
            readMapFile();
            h.postDelayed(this, 4000);
        }
    };

    /**
     * the picture of the world. the plugin's map wins when it is there (it knows the biomes),
     * otherwise the app reads the region files itself, which works for every server software.
     */
    private void readMapFile() {
        try {
            if (worldGen == null) return;
            java.io.File file = new java.io.File(mapDir(), "map.json");
            if (!file.isFile()) file = new java.io.File(otherMapDir(), "map.json");
            if (!file.isFile()) {
                String own = eu.kodanetwork.mchost.util.WorldMapReader.read(
                        new java.io.File(server.getServerDir()),
                        App.getPrefs(this).getInt("pregenerate_radius", 1000), 96);
                if (own != null) {
                    worldGen.setRealMap(own);
                    if (own.indexOf('1') >= 0) {
                        showWaitAnimation(false);
                        if (logScroll != null) logScroll.setAlpha(1f);
                    }
                }
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            String json = sb.toString();
            int cells = json.indexOf("\"cells\":\"");
            if (cells < 0) return;
            int start = cells + 9;
            int end = json.indexOf('"', start);
            if (end < 0) return;
            String grid = json.substring(start, end);
            worldGen.setRealMap(grid);
            // the picture is real even before chunky reports numbers, so stop the wait animation
            if (grid.indexOf('1') >= 0 || grid.indexOf('2') >= 0 || grid.indexOf('3') >= 0) {
                showWaitAnimation(false);
                if (logScroll != null) logScroll.setAlpha(1f);
            }
        } catch (Exception ignored) {}
    }

    private final Runnable pollProgress = new Runnable() {
        @Override public void run() {
            if (server != null && svc != null && server.state == ServerInstance.State.ONLINE) {
                svc.sendCmd(server.getId(), "chunky progress");
            }
            h.postDelayed(this, PROGRESS_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pregeneration);

        repo = ServerRepo.get(this);
        String id = getIntent() != null ? getIntent().getStringExtra(EXTRA_SERVER_ID) : null;
        server = id != null ? repo.byId(id) : null;
        if (server == null) {
            finish();
            return;
        }

        tvHead = findViewById(R.id.tv_pregen_head);
        tvState = findViewById(R.id.tv_pregen_state);
        tvPercent = findViewById(R.id.tv_pregen_percent);
        tvEta = findViewById(R.id.tv_pregen_eta);
        tvLog = findViewById(R.id.tv_pregen_log);
        tvStats = findViewById(R.id.tv_pregen_stats);
        progressBar = findViewById(R.id.pb_pregen);
        logScroll = findViewById(R.id.sv_pregen_log);
        worldGen = findViewById(R.id.worldgen);
        lottieWait = findViewById(R.id.lottie_pregen_wait);

        tvHead.setText(server.getName());
        findViewById(R.id.btn_pregen_back).setOnClickListener(v -> {
            HapticUtil.forceVibrate(this, 40);
            finish();
        });
        findViewById(R.id.btn_pregen_action).setOnClickListener(v -> onActionPressed());
        // the screen takes care of the boring parts itself: jar first, eula question second
        prepareAndStart();

        Intent bind = new Intent(this, KodaServerService.class);
        bindService(bind, conn, Context.BIND_AUTO_CREATE);
        writeMapRequest();
        h.postDelayed(mapPoll, 3000);
        refreshState();
        ThemeHelper.apply(this);
    }

    /** done means done: the live screen closes and the normal server screen takes over. */
    private void openServerDashboard() {
        if (isFinishing() || isDestroyed()) return;
        android.content.Intent i = new android.content.Intent(this, ServerDetailActivity.class);
        i.putExtra("id", server.getId());
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(pollProgress);
        h.removeCallbacks(mapPoll);
        // tell the plugin to stop sampling, it costs ticks we do not need anymore
        try {
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(mapDir(), "map_request.json"))) {
                w.write("{\"enabled\":false}");
            }
        } catch (Exception ignored) {}
        if (bound && svc != null) {
            if (logCb != null) svc.removeLogCb(logCb);
            if (stateCb != null) svc.removeStateCb(stateCb);
            try { unbindService(conn); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    /** the server jar is a precondition for everything, and it may still be missing. */
    private boolean hasServerJar() {
        java.io.File dir = new java.io.File(server.getServerDir());
        java.io.File[] root = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (root != null && root.length > 0) return true;
        java.io.File[] subs = dir.listFiles(java.io.File::isDirectory);
        if (subs != null) {
            for (java.io.File sub : subs) {
                String n = sub.getName().toLowerCase();
                if (n.equals("plugins") || n.equals("mods") || n.equals("libraries") || n.equals("logs")
                        || n.equals("cache") || n.equals("world") || n.startsWith("world_") || n.equals(".sys")) {
                    continue;
                }
                java.io.File[] jars = sub.listFiles((d, name) -> name.endsWith(".jar"));
                if (jars != null && jars.length > 0) return true;
            }
        }
        return false;
    }

    /** true when eula.txt already says yes. */
    private boolean eulaAccepted() {
        java.io.File eula = new java.io.File(server.getServerDir(), "eula.txt");
        if (!eula.isFile()) return false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(eula))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().equalsIgnoreCase("eula=true")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * gets the server ready without asking anything the owner does not have to decide:
     * the jar downloads itself when it is missing, and the eula stays a decision, so the
     * button for it appears instead of accepting it silently.
     */
    private void prepareAndStart() {
        if (server == null) return;
        if (!hasServerJar()) {
            downloadJar();
            return;
        }
        if (!eulaAccepted()) {
            showAction(R.string.pregen_action_eula);
            tvState.setText(R.string.pregen_state_eula);
            return;
        }
        showAction(0);
        startServer();
    }

    private void onActionPressed() {
        HapticUtil.forceVibrate(this, 60);
        if (!hasServerJar()) {
            prepareAndStart();
            return;
        }
        if (!eulaAccepted()) {
            try (java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(server.getServerDir(), "eula.txt"))) {
                w.write("#Accepted in the KodaHosting pre-generation screen\n");
                w.write("eula=true\n");
            } catch (Exception ignored) {}
            showAction(0);
            prepareAndStart();
        }
    }

    /** shows the one button that matters right now, 0 hides it. */
    private void showAction(int labelRes) {
        View button = findViewById(R.id.btn_pregen_action);
        if (button == null) return;
        button.setVisibility(labelRes == 0 ? View.GONE : View.VISIBLE);
        if (labelRes != 0) ((android.widget.TextView) button).setText(labelRes);
    }

    /** the jar is still missing, so fetch it first and keep the console as the progress bar. */
    private void downloadJar() {
        tvState.setText(R.string.pregen_state_download);
        showAction(0);
        new eu.kodanetwork.mchost.network.JarDownloader(this).download(server,
                new eu.kodanetwork.mchost.network.JarDownloader.Cb() {
                    @Override public void onProgress(int pct, String msg) {
                        runOnUiThread(() -> onLogLine("KodaTransfer: " + msg + " (" + pct + "%)"));
                    }
                    @Override public void onDone(java.io.File jar) {
                        runOnUiThread(() -> {
                            onLogLine("Server jar ready: " + jar.getName());
                            prepareAndStart();
                        });
                    }
                    @Override public void onError(String err) {
                        runOnUiThread(() -> {
                            tvState.setText(getString(R.string.pregen_state_download_failed, err));
                            showAction(R.string.pregen_action_retry);
                        });
                    }
                });
    }

    /** sends the start action once, for a server that is not running yet. */
    private void startServer() {
        ServerInstance.State s = server.state;
        if (s == ServerInstance.State.ONLINE || s == ServerInstance.State.STARTING
                || s == ServerInstance.State.INSTALLING || s == ServerInstance.State.SETTING_UP) {
            return;
        }
        server.state = ServerInstance.State.STARTING;
        repo.update(server);
        Intent start = new Intent(this, KodaServerService.class);
        start.setAction(KodaServerService.ACTION_START);
        start.putExtra(KodaServerService.EXTRA_ID, server.getId());
        startService(start);
    }

    /** switches between the hopping blocks (before) and the growing world map (while running). */
    private void showWaitAnimation(boolean waiting) {
        if (lottieWait != null) {
            lottieWait.setVisibility(waiting ? View.VISIBLE : View.GONE);
            if (waiting) lottieWait.playAnimation(); else lottieWait.pauseAnimation();
        }
        if (worldGen != null) worldGen.setVisibility(waiting ? View.GONE : View.VISIBLE);
    }

    /** the line under the animation and the phase of the picture. */
    private void refreshState() {
        if (server == null) return;
        ServerInstance.State s = server.state;

        int radius = App.getPrefs(this).getInt("pregenerate_radius", 1000);
        String label;
        WorldGenView.Phase phase;
        switch (s) {
            case ONLINE:
                label = getString(R.string.pregen_state_running, radius);
                phase = lastPercent >= 0 ? WorldGenView.Phase.GENERATING : WorldGenView.Phase.SETUP;
                break;
            case STARTING:
                label = getString(R.string.pregen_state_starting);
                phase = WorldGenView.Phase.WAITING;
                break;
            case INSTALLING:
            case SETTING_UP:
                label = getString(R.string.pregen_state_installing);
                phase = WorldGenView.Phase.SETUP;
                break;
            default:
                label = getString(R.string.pregen_state_offline);
                phase = WorldGenView.Phase.WAITING;
                break;
        }
        tvState.setText(label);
        if (worldGen != null) worldGen.setPhase(phase);
        boolean waiting = phase == WorldGenView.Phase.WAITING || phase == WorldGenView.Phase.SETUP;
        showWaitAnimation(waiting && !finishedAnnounced);
        // while nothing generates yet the console is only a backdrop for the hopping blocks
        if (logScroll != null) logScroll.setAlpha(waiting || finishedAnnounced ? 0.35f : 1f);
        updateStats();
    }

    /** the real numbers under the bar: chunks done, total and how fast it is going. */
    private void updateStats() {
        if (tvStats == null || server == null) return;
        long total = totalChunks();
        java.util.List<String> bits = new java.util.ArrayList<>();
        bits.add(getString(R.string.pregen_stats_world, worldName()));
        if (doneChunks > 0) {
            bits.add(getString(R.string.pregen_stats_chunks, doneChunks, total));
            if (chunksPerHour > 1) {
                bits.add(getString(R.string.pregen_stats_rate, String.format(java.util.Locale.US, "%.0f", chunksPerHour / 3600d)));
            }
        } else {
            bits.add(getString(R.string.pregen_stats_chunks_unknown, total));
        }
        bits.add(getString(R.string.pregen_stats_radius, App.getPrefs(this).getInt("pregenerate_radius", 1000)));
        tvStats.setText(android.text.TextUtils.join("  \u00b7  ", bits));
    }

    /** the level name, or "world" when the server has its own idea. */
    private String worldName() {
        java.io.File props = new java.io.File(server.getServerDir(), "server.properties");
        if (props.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(props))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("level-name=")) return line.substring(11).trim();
                }
            } catch (Exception ignored) {}
        }
        return "world";
    }

    /** only chunky's own lines and the pre-generation notes are interesting here. */
    private boolean interesting(String line) {
        String lower = line.toLowerCase();
        return lower.contains("chunk") || lower.contains("pre-generat") || lower.contains("pregenerat");
    }

    /** every console line lands here: it goes into the visible tail and gets parsed. */
    private void onLogLine(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String line = raw.replaceAll("\\[[0-9;]*m", "").trim();
        if (line.isEmpty() || !interesting(line)) return;

        logText.append(line).append('\n');
        String[] lines = logText.toString().split("\n");
        if (lines.length > LOG_LINES) {
            logText.setLength(0);
            for (int i = lines.length - LOG_LINES; i < lines.length; i++) logText.append(lines[i]).append('\n');
        }
        if (tvLog != null) {
            tvLog.setText(logText.toString());
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }

        // nothing counts before the service really started the task, and neither does a
        // lone 100% that shows up while the server is still booting
        if (svc == null || !svc.isPregenerationStarted(server.getId())) return;

        Double percent = percentOf(line);
        if (percent != null) applyPercent(percent);
        if (looksFinished(line) && !finishedAnnounced && sawBelow100) {
            applyPercent(100d);
        }
    }

    /** chunky reports either a percentage or a chunk count, both are good enough. */
    private Double percentOf(String line) {
        String lower = line.toLowerCase();
        // chunky prefixes its lines differently per loader, the word chunk is the reliable part
        boolean chunky = lower.contains("chunk") || lower.contains("generating") || lower.contains("pre-generat");
        if (!chunky) return null;

        Matcher percent = PERCENT.matcher(line);
        if (percent.find()) {
            try {
                double p = Double.parseDouble(percent.group(1).replace(",", "."));
                if (p >= 0 && p <= 100) return p;
            } catch (Exception ignored) {}
        }

        Matcher chunks = CHUNKS.matcher(line);
        if (chunks.find()) {
            try {
                long done = Long.parseLong(chunks.group(1).replaceAll("[.,]", ""));
                long total = totalChunks();
                doneChunks = done;
                if (total > 0) return Math.min(100d, (done * 100d) / total);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** true when the chunk counter already came from chunky itself in this sample. */
    private boolean doneChunksEstimated(double percent) {
        return doneChunks > 0;
    }

    /** true once chunky reported real progress, so "done" never shows up before that. */
    private boolean sawProgress() {
        return lastPercent > 0 || doneChunks > 0;
    }

    /** chunky says "Task finished"/"generation ... complete" when it is really done. */
    private boolean looksFinished(String line) {
        String lower = line.toLowerCase();
        return (lower.contains("task") && (lower.contains("finish") || lower.contains("complete")))
                || lower.contains("pre-generation finished")
                || (lower.contains("generation") && lower.contains("complete"));
    }

    private long totalChunks() {
        int radius = App.getPrefs(this).getInt("pregenerate_radius", 1000);
        long perSide = Math.max(1, (2L * radius) / 16);
        return perSide * perSide;
    }

    /** takes a fresh percentage sample and turns the two samples into a time left. */
    private void applyPercent(double percent) {
        if (progressBar != null) progressBar.setProgress((int) Math.round(percent));
        if (tvPercent != null) tvPercent.setText(getString(R.string.pregen_percent, String.format(java.util.Locale.US, "%.1f", percent)));
        if (worldGen != null) {
            worldGen.setPhase(percent > 0 ? WorldGenView.Phase.GENERATING : WorldGenView.Phase.SETUP);
            worldGen.setProgress((float) (percent / 100d));
        }
        if (percent > 0) {
            sawBelow100 = sawBelow100 || percent < 100;
            showWaitAnimation(false);
            if (logScroll != null) logScroll.setAlpha(1f);
        }
        if (!doneChunksEstimated(percent)) {
            // chunky sometimes only reports a percentage, derive the chunk count from it
            long total = totalChunks();
            doneChunks = Math.round(total * percent / 100d);
        }
        // finished: celebrate briefly, then hand over to the server dashboard by itself.
        // a single 100% right after the boot is not a finished task, the number has to
        // have been below that at least once in this screen.
        if (!finishedAnnounced && percent >= 100 && sawBelow100) {
            finishedAnnounced = true;
            HapticUtil.forceVibrate(this, 120);
            tvState.setText(R.string.pregen_state_done);
            if (tvEta != null) tvEta.setText(R.string.pregen_done);
            if (worldGen != null) {
                worldGen.setPhase(WorldGenView.Phase.DONE);
                worldGen.setProgress(1f);
            }
            android.widget.Toast.makeText(this, R.string.pregen_toast_done, android.widget.Toast.LENGTH_LONG).show();
            h.postDelayed(this::openServerDashboard, 3000);
        }
        updateStats();

        long now = System.currentTimeMillis();
        if (lastPercent >= 0 && now > lastPercentAt && percent > lastPercent) {
            double gained = percent - lastPercent;
            double hours = (now - lastPercentAt) / 3_600_000d;
            double rate = gained / Math.max(hours, 1e-6);
            // smooth it, chunky reports in bursts and a single gap would jump wildly
            percentPerHour = percentPerHour <= 0 ? rate : (percentPerHour * 0.6 + rate * 0.4);
            long total = totalChunks();
            chunksPerHour = total > 0 ? percentPerHour * total / 100d : 0;
        }
        lastPercent = percent;
        lastPercentAt = now;

        if (tvEta == null) return;
        if (percent >= 100 && sawProgress()) {
            tvEta.setText(R.string.pregen_done);
        } else if (percentPerHour > 0.01) {
            double hoursLeft = (100 - percent) / percentPerHour;
            tvEta.setText(hoursLeft < 1
                    ? getString(R.string.pregen_eta_minutes, Math.max(1, (int) Math.round(hoursLeft * 60)))
                    : getString(R.string.pregen_eta_hours, hoursLeft));
        } else {
            tvEta.setText(R.string.pregen_eta_unknown);
        }
    }
}
