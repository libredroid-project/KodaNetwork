/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.kodanetwork.mchost.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.service.KodaServerService;

/**
 * server cards for the website design mode. the card mirrors the dashboard cards on
 * the website: glass panel, real server icon, mono metrics and three pill actions.
 */
public class WebsiteServerAdapter extends RecyclerView.Adapter<WebsiteServerAdapter.VH> {

    public interface OnOpen {
        void onOpen(ServerInstance server);
    }

    private static final java.util.Map<String, Bitmap> iconCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Context ctx;
    private final OnOpen listener;
    private static final String PAYLOAD_LIVE = "live";

    private final List<ServerInstance> servers = new ArrayList<>();
    private final java.util.Set<String> introDone = new java.util.HashSet<>();
    private String lastStructural = "";
    private String lastLive = "";

    public WebsiteServerAdapter(Context context, List<ServerInstance> initial, OnOpen listener) {
        this.ctx = context;
        this.listener = listener;
        if (initial != null) servers.addAll(initial);
        lastStructural = structuralSignature(servers);
        lastLive = liveSignature(servers);
    }

    public void update(List<ServerInstance> fresh) {
        List<ServerInstance> next = fresh == null ? java.util.Collections.emptyList() : fresh;
        String structural = structuralSignature(next);
        String live = liveSignature(next);
        boolean structuralChanged = !structural.equals(lastStructural);
        boolean liveChanged = !live.equals(lastLive);
        servers.clear();
        servers.addAll(next);
        if (structuralChanged) {
            lastStructural = structural;
            lastLive = live;
            notifyDataSetChanged();
        } else if (liveChanged) {
            lastLive = live;
            // only the numbers change: rebind the visible cards with a payload instead
            // of replacing the whole list every second
            for (int i = 0; i < servers.size(); i++) notifyItemChanged(i, PAYLOAD_LIVE);
        }
    }

    /** identity, state and layout fields. a change here needs a full bind. */
    private static String structuralSignature(List<ServerInstance> list) {
        StringBuilder sb = new StringBuilder();
        for (ServerInstance s : list) {
            sb.append(s.getId()).append('|')
                    .append(s.state).append('|')
                    .append(s.getMaxPlayers()).append('|')
                    .append(s.getRamMB()).append('|')
                    .append(s.getVersion()).append('|')
                    .append(s.banned).append('|')
                    .append(s.getName()).append('|')
                    .append(s.getJoinAddress()).append('\n');
        }
        return sb.toString();
    }

    /** live numbers. these refresh with a cheap payload bind. */
    private static String liveSignature(List<ServerInstance> list) {
        StringBuilder sb = new StringBuilder();
        for (ServerInstance s : list) {
            sb.append(s.onlinePlayers).append('|')
                    .append(s.ramUsageMB).append('|')
                    .append(s.currentTps).append('|')
                    .append(s.getFormattedUptime()).append('\n');
        }
        return sb.toString();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server_website, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ServerInstance server = servers.get(position);
        if (introDone.size() < 8 && !introDone.contains(server.getId())) {
            introDone.add(server.getId());
            eu.kodanetwork.mchost.util.KodaMotion.revealItem(holder.itemView, introDone.size() - 1);
        }
        holder.bind(server);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_LIVE)) {
            holder.bindLive(servers.get(position));
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    @Override
    public int getItemCount() {
        return servers.size();
    }

    private static String memoryUsageLabel(int usedMb, int totalMb) {
        if (totalMb <= 0) return "--";
        int used = Math.max(0, usedMb);
        if (totalMb >= 1024) {
            return String.format(Locale.US, "%.1f / %.0f GB", used / 1024.0, totalMb / 1024.0);
        }
        return used + " / " + totalMb + " MB";
    }

    private static String versionLabel(ServerInstance server) {
        String type = server.getType() == null ? "" : server.getType().name();
        if (!type.isEmpty()) {
            type = type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
        }
        String version = server.getVersion() == null ? "" : server.getVersion().trim();
        if (version.isEmpty()) return type;
        return type.isEmpty() ? version : type + " " + version;
    }

    private void send(ServerInstance server, String action) {
        Intent intent = new Intent(ctx, KodaServerService.class);
        intent.setAction(action);
        intent.putExtra(KodaServerService.EXTRA_ID, server.getId());
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent);
        else ctx.startService(intent);
    }

    private void copyAddress(String address) {
        ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("KodaHosting address", address));
        }
    }

    class VH extends RecyclerView.ViewHolder {
        final TextView name, address, players, ram, version, meta, start, restart, stop;
        final android.widget.ProgressBar ramBar, startSpin, restartSpin, stopSpin;
        final View iconFrame;
        final ImageView icon, copy;

        VH(View view) {
            super(view);
            name = view.findViewById(R.id.tv_name);
            address = view.findViewById(R.id.tv_address);
            players = view.findViewById(R.id.tv_players);
            ram = view.findViewById(R.id.tv_ram);
            version = view.findViewById(R.id.tv_version);
            meta = view.findViewById(R.id.tv_meta);
            ramBar = view.findViewById(R.id.pb_ram);
            startSpin = view.findViewById(R.id.pb_start_spin);
            restartSpin = view.findViewById(R.id.pb_restart_spin);
            stopSpin = view.findViewById(R.id.pb_stop_spin);
            start = view.findViewById(R.id.btn_start);
            restart = view.findViewById(R.id.btn_restart);
            stop = view.findViewById(R.id.btn_stop);
            iconFrame = view.findViewById(R.id.icon_status_frame);
            icon = view.findViewById(R.id.iv_server_icon);
            copy = view.findViewById(R.id.iv_copy);
        }

        void bind(ServerInstance server) {
            String displayName = server.getName() == null || server.getName().trim().isEmpty()
                    ? server.getSubdomain() : server.getName();
            name.setText(displayName);
            address.setText(server.getJoinAddress());

            int color;
            String label;
            if (server.banned) {
                color = 0xFFEF4444;
                label = "Banned";
            } else {
                switch (server.state) {
                    case ONLINE:
                        color = 0xFF4ADE80;
                        label = "Online";
                        break;
                    case STARTING:
                    case RESTARTING:
                        color = 0xFFFBBF24;
                        label = "Starting";
                        break;
                    case STOPPING:
                        color = 0xFFF87171;
                        label = "Stopping";
                        break;
                    case INSTALLING:
                    case SETTING_UP:
                        color = 0xFFFBBF24;
                        label = "Setting up";
                        break;
                    case CRASHED:
                        color = 0xFFEF4444;
                        label = "Crashed";
                        break;
                    case HIBERNATED:
                        color = 0xFF60A5FA;
                        label = "Hibernated";
                        break;
                    default:
                        color = 0xFFA1A1AA;
                        label = "Offline";
                        break;
                }
            }

            // the server icon carries the status as a spinning/settling coloured ring
            boolean busy = server.state == ServerInstance.State.STARTING
                    || server.state == ServerInstance.State.RESTARTING
                    || server.state == ServerInstance.State.STOPPING
                    || server.state == ServerInstance.State.INSTALLING
                    || server.state == ServerInstance.State.SETTING_UP;
            applyStatusRing(color, label, busy);

            version.setText(versionLabel(server));
            bindLive(server);
            bindIcon(server);

            boolean running = server.state == ServerInstance.State.ONLINE
                    || server.state == ServerInstance.State.STARTING
                    || server.state == ServerInstance.State.RESTARTING;
            start.setEnabled(!running);
            start.setAlpha(running ? 0.45f : 1f);
            stop.setEnabled(running);
            stop.setAlpha(running ? 1f : 0.45f);
            restart.setEnabled(server.state != ServerInstance.State.HIBERNATED);
            restart.setAlpha(server.state == ServerInstance.State.HIBERNATED ? 0.45f : 1f);

            // the button that is doing the work carries an indeterminate spinner
            setLoading(start, startSpin, server.state == ServerInstance.State.STARTING
                    || server.state == ServerInstance.State.INSTALLING
                    || server.state == ServerInstance.State.SETTING_UP, 0xFFFFFFFF);
            setLoading(restart, restartSpin,
                    server.state == ServerInstance.State.RESTARTING, 0xFFA1A1AA);
            setLoading(stop, stopSpin,
                    server.state == ServerInstance.State.STOPPING, 0xFFF87171);

            start.setOnClickListener(v -> {
                pulse(v);
                showPending("Starting", 0xFFFBBF24, start, startSpin);
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(ctx, 40);
                send(server, KodaServerService.ACTION_START);
            });
            restart.setOnClickListener(v -> {
                pulse(v);
                showPending("Restarting", 0xFFFBBF24, restart, restartSpin);
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(ctx, 40);
                send(server, KodaServerService.ACTION_RESTART);
            });
            stop.setOnClickListener(v -> {
                pulse(v);
                showPending("Stopping", 0xFFF87171, stop, stopSpin);
                eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(ctx, 40);
                send(server, KodaServerService.ACTION_STOP);
            });
            copy.setOnClickListener(v -> {
                copyAddress(server.getJoinAddress());
                copy.animate().cancel();
                copy.setImageResource(R.drawable.ic_website_check);
                copy.setImageTintList(null);
                copy.animate().scaleX(1.2f).scaleY(1.2f).setDuration(110)
                        .withEndAction(() -> copy.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
                        .start();
                copy.postDelayed(() -> {
                    copy.setImageResource(R.drawable.ic_website_copy);
                    copy.setImageTintList(null);
                }, 1200);
            });

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onOpen(server);
            });
        }

        /** the cheap bind for the once-per-second numbers: no layout, no icon work. */
        void bindLive(ServerInstance server) {
            players.setText(server.onlinePlayers + " / " + server.getMaxPlayers());
            int totalRam = Math.max(0, server.getRamMB());
            int usedRam = Math.max(0, server.ramUsageMB);
            ram.setText(memoryUsageLabel(usedRam, totalRam));
            int usedPercent = totalRam > 0 ? Math.min(100, (usedRam * 100) / totalRam) : 0;
            int memoryColor = usedPercent >= 85 ? 0xFFEF4444
                    : usedPercent >= 60 ? 0xFFFBBF24 : 0xFF4ADE80;
            if (ramBar != null) {
                ramBar.setMax(Math.max(1, totalRam));
                ramBar.setProgress(Math.min(usedRam, totalRam));
                ramBar.setProgressTintList(android.content.res.ColorStateList.valueOf(memoryColor));
            }
            String tps = server.state == ServerInstance.State.ONLINE
                    ? String.format(Locale.US, "%.1f", server.currentTps) : "--";
            meta.setText(ctx.getString(R.string.website_meta, server.getFormattedUptime(), tps));
        }

        private void setLoading(TextView label, android.widget.ProgressBar spinner,
                                 boolean loading, int normalColor) {
            // keep the pill visible: only the text disappears behind the spinner
            label.setTextColor(loading ? 0x00000000 : normalColor);
            if (spinner != null) spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        }

        /** the service can finish a stop before the next one second poll, so the card
         *  shows the transition immediately instead of keeping the old Online word. */
        private void applyStatusRing(int color, String label, boolean busy) {
            if (iconFrame == null) return;
            iconFrame.setContentDescription(label);
            if (iconFrame instanceof StatusRingFrame) {
                ((StatusRingFrame) iconFrame).setState(color, busy);
            }
        }

        private void showPending(String label, int color, TextView labelView,
                                 android.widget.ProgressBar spinner) {
            applyStatusRing(color, label, true);
            setLoading(start, startSpin, false, 0xFFFFFFFF);
            setLoading(restart, restartSpin, false, 0xFFA1A1AA);
            setLoading(stop, stopSpin, false, 0xFFF87171);
            setLoading(labelView, spinner, true, 0xFFFFFFFF);
            start.setEnabled(false);
            stop.setEnabled(false);
            restart.setEnabled(false);
        }

        private void pulse(android.view.View view) {
            view.animate().cancel();
            view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80)
                    .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                            .start())
                    .start();
        }

        /** real server-icon.png, with the orange server glyph as the fallback. */
        private void bindIcon(ServerInstance server) {
            String dir = server.getServerDir();
            File iconFile = dir == null ? null : new File(dir, "server-icon.png");
            if (iconFile != null && iconFile.exists()) {
                final String path = iconFile.getAbsolutePath();
                icon.setPadding(0, 0, 0, 0);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setImageTintList(null);
                icon.clearColorFilter();
                if (iconCache.containsKey(path)) {
                    icon.setImageBitmap(iconCache.get(path));
                    icon.setTag(null);
                } else {
                    icon.setTag(path);
                    new Thread(() -> {
                        Bitmap bitmap = BitmapFactory.decodeFile(path);
                        if (bitmap != null) iconCache.put(path, bitmap);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (path.equals(icon.getTag())) {
                                icon.setImageBitmap(bitmap);
                            }
                        });
                    }).start();
                }
            } else {
                icon.setTag(null);
                icon.setImageTintList(null);
                icon.setPadding((int) (7 * ctx.getResources().getDisplayMetrics().density),
                        (int) (7 * ctx.getResources().getDisplayMetrics().density),
                        (int) (7 * ctx.getResources().getDisplayMetrics().density),
                        (int) (7 * ctx.getResources().getDisplayMetrics().density));
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setImageResource(R.drawable.ic_server_simple);
                icon.setColorFilter(0xFFFF8C38);
            }
        }
    }
}
