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

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import eu.kodanetwork.mchost.model.ServerInstance;

/**
 * scheduled automation per server: restarts, stops, commands, announcements and saves.
 *
 * the app is the only executor. it owns {@code filesDir/schedules/<serverId>.json} and runs
 * due jobs from the hosting service. the same list is mirrored read-only into
 * {@code plugins/KodaDash/schedule.json} so the dashboard can show what is planned. that
 * keeps exactly one scheduler alive and the feature works without the dashboard plugin.
 *
 * jobs are daily at a fixed time or every N minutes. the optional "only when empty" flag
 * skips a run while players are online instead of kicking them out.
 */
public final class ScheduleStore {

    private static final String TAG = "KodaSchedule";
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("EEE HH:mm", Locale.getDefault());

    /** the job types the service can run. */
    public static final String[] TYPES = {"restart", "stop", "announce", "command", "save"};

    private ScheduleStore() {
    }

    /** one scheduled job. */
    public static class Job {
        public String id = UUID.randomUUID().toString().substring(0, 8);
        public String type = "save";
        public String value = "";
        public String mode = "interval";
        public String time = "04:00";
        public int intervalMinutes = 180;
        public boolean onlyWhenEmpty = false;
        public boolean enabled = true;
        public long lastRun = 0L;
        public long nextRun = 0L;

        public JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("type", type);
            json.put("value", value);
            json.put("mode", mode);
            json.put("time", time);
            json.put("intervalMinutes", intervalMinutes);
            json.put("onlyWhenEmpty", onlyWhenEmpty);
            json.put("enabled", enabled);
            json.put("lastRun", lastRun);
            json.put("nextRun", nextRun);
            return json;
        }

        public static Job fromJson(JSONObject json) {
            Job job = new Job();
            job.id = json.optString("id", job.id);
            job.type = json.optString("type", "save");
            job.value = json.optString("value", "");
            job.mode = json.optString("mode", "interval");
            job.time = json.optString("time", "04:00");
            job.intervalMinutes = json.optInt("intervalMinutes", 180);
            job.onlyWhenEmpty = json.optBoolean("onlyWhenEmpty", false);
            job.enabled = json.optBoolean("enabled", true);
            job.lastRun = json.optLong("lastRun", 0L);
            job.nextRun = json.optLong("nextRun", 0L);
            return job;
        }

        /** readable form, like "every 180 min, only when empty". */
        public String describe() {
            StringBuilder builder = new StringBuilder();
            if ("daily".equals(mode)) builder.append("daily at ").append(time);
            else builder.append("every ").append(intervalMinutes).append(" min");
            if (onlyWhenEmpty) builder.append(", only when empty");
            return builder.toString();
        }

        /** next run as text, in device local time. */
        public String nextRunText() {
            if (nextRun <= 0L) return "";
            return STAMP.format(new Date(nextRun));
        }
    }

    /** the file holding the jobs of one server. */
    public static File fileFor(Context ctx, String serverId) {
        File dir = new File(ctx.getFilesDir(), "schedules");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create schedule directory");
        return new File(dir, serverId + ".json");
    }

    public static List<Job> list(Context ctx, String serverId) {
        List<Job> jobs = new ArrayList<>();
        File file = fileFor(ctx, serverId);
        if (!file.isFile()) return jobs;
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                jobs.add(Job.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            // older android java has no Files.readAllBytes, so the reader is the fallback
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                JSONArray array = new JSONArray(sb.toString());
                for (int i = 0; i < array.length(); i++) jobs.add(Job.fromJson(array.getJSONObject(i)));
            } catch (Exception inner) {
                Log.w(TAG, "Could not read schedules: " + inner.getMessage());
            }
        }
        return jobs;
    }

    public static void save(Context ctx, String serverId, List<Job> jobs, ServerInstance srv) {
        JSONArray array = new JSONArray();
        for (Job job : jobs) {
            try {
                array.put(job.toJson());
            } catch (Exception ignored) {
            }
        }
        try (FileOutputStream out = new FileOutputStream(fileFor(ctx, serverId))) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Could not write schedules: " + e.getMessage());
        }
        writeDashboardMirror(ctx, srv, jobs);
    }

    public static Job find(Context ctx, String serverId, String jobId) {
        for (Job job : list(ctx, serverId)) {
            if (job.id.equals(jobId)) return job;
        }
        return null;
    }

    /** adds a job or replaces the old one, returns what got stored. */
    public static Job upsert(Context ctx, String serverId, Job incoming, ServerInstance srv) {
        List<Job> jobs = list(ctx, serverId);
        if (incoming.id == null || incoming.id.isEmpty()) incoming.id = UUID.randomUUID().toString().substring(0, 8);
        boolean replaced = false;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id.equals(incoming.id)) {
                incoming.lastRun = jobs.get(i).lastRun;
                jobs.set(i, incoming);
                replaced = true;
                break;
            }
        }
        if (!replaced) jobs.add(incoming);
        incoming.nextRun = computeNextRun(incoming);
        save(ctx, serverId, jobs, srv);
        return incoming;
    }

    public static boolean remove(Context ctx, String serverId, String jobId, ServerInstance srv) {
        List<Job> jobs = list(ctx, serverId);
        boolean removed = false;
        for (int i = jobs.size() - 1; i >= 0; i--) {
            if (jobs.get(i).id.equals(jobId)) {
                jobs.remove(i);
                removed = true;
            }
        }
        if (removed) save(ctx, serverId, jobs, srv);
        return removed;
    }

    /** the next run timestamp, in the phone's local time. */
    public static long computeNextRun(Job job) {
        long now = System.currentTimeMillis();
        if ("daily".equals(job.mode)) {
            try {
                String[] parts = job.time.split(":");
                Calendar target = Calendar.getInstance();
                target.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0].trim()));
                target.set(Calendar.MINUTE, Integer.parseInt(parts[1].trim()));
                target.set(Calendar.SECOND, 0);
                target.set(Calendar.MILLISECOND, 0);
                if (target.getTimeInMillis() <= now) target.add(Calendar.DAY_OF_MONTH, 1);
                return target.getTimeInMillis();
            } catch (Exception e) {
                return now + 60L * 60L * 1000L;
            }
        }
        int minutes = Math.max(1, Math.min(10080, job.intervalMinutes));
        long base = job.lastRun > 0 ? job.lastRun : now;
        return base + minutes * 60_000L;
    }

    public static String timeText(String time) {
        return time;
    }

    public static String formatTime(long millis) {
        return TIME.format(new Date(millis));
    }

    /**
     * mirrors the job list into the server folder so the dashboard can display it. the
     * dashboard never executes these jobs, it only shows what the app has planned.
     */
    public static void writeDashboardMirror(Context ctx, ServerInstance srv, List<Job> jobs) {
        if (srv == null) return;
        try {
            JSONArray array = new JSONArray();
            for (Job job : jobs) array.put(job.toJson());
            JSONObject root = new JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            root.put("executor", "app");
            root.put("jobs", array);

            File dir = new File(srv.getServerDir(), "plugins/KodaDash");
            if (!dir.exists() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, "schedule.json"))) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write the dashboard schedule mirror: " + e.getMessage());
        }
    }
}
