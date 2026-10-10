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
package eu.kodanetwork.mchost.extension;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.service.KodaServerService;

/**
 * the single choke point for everything an extension calls through window.koda.
 *
 * API version 1 exposes read-only methods only, every call is checked against
 * the permissions from the manifest, and failures never leave this class as
 * exceptions: the responder always gets {"error": "..."} instead. secrets
 * (device token, E2EE, dashboard token, admin command channel) are out of
 * reach from here by construction.
 */
public class ExtensionBridge {

    public interface Responder { void respond(String json); }

    private static final int MAX_CALLS_PER_SECOND = 40;
    private static final int MAX_LOG_TAIL = 1000;

    private final Activity activity;
    private final ExtensionRepository.InstalledExtension ext;
    private final ExtensionStorage storage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private KodaServerService service;

    private long rateWindowStart = 0L;
    private int rateWindowCount = 0;

    public ExtensionBridge(Activity activity, ExtensionRepository.InstalledExtension ext) {
        this.activity = activity;
        this.ext = ext;
        this.storage = new ExtensionStorage(ext.dataDir);
    }

    public void setService(KodaServerService service) { this.service = service; }

    /**
     * runs one bridge call. the responder fires exactly once, on some arbitrary
     * thread; async methods (ui.confirm) answer only once the user decided.
     */
    public void dispatch(String payloadJson, Responder responder) {
        final Responder safe = json -> { try { responder.respond(json); } catch (Throwable ignored) {} };
        if (!rateOk()) { safe.respond(err("rate limited")); return; }

        final String method;
        final JSONArray args;
        try {
            JSONObject o = new JSONObject(payloadJson);
            method = o.optString("method", "");
            JSONArray a = o.optJSONArray("args");
            args = a == null ? new JSONArray() : a;
        } catch (Throwable t) {
            safe.respond(err("malformed call"));
            return;
        }

        try {
            switch (method) {
                case "locale": {
                    String lang = "en";
                    try {
                        lang = activity.getResources().getConfiguration().getLocales().get(0).getLanguage();
                    } catch (Throwable ignored) {}
                    JSONObject r = new JSONObject();
                    r.put("lang", lang);
                    safe.respond(r.toString());
                    break;
                }
                case "theme": {
                    safe.respond(themeJson().toString());
                    break;
                }
                case "ui.toast": {
                    final String msg = args.optString(0, "");
                    main.post(() -> {
                        try { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                    });
                    safe.respond(ok());
                    break;
                }
                case "ui.confirm": {
                    final String title = args.optString(0, "");
                    final String msg = args.optString(1, "");
                    main.post(() -> {
                        try {
                            new androidx.appcompat.app.AlertDialog.Builder(activity)
                                    .setTitle(title)
                                    .setMessage(msg)
                                    .setPositiveButton(android.R.string.ok, (d, w) -> safe.respond(ok()))
                                    .setNegativeButton(android.R.string.cancel, (d, w) -> safe.respond("{\"ok\":false}"))
                                    .setOnCancelListener(d -> safe.respond("{\"ok\":false}"))
                                    .show();
                        } catch (Throwable t) {
                            safe.respond("{\"ok\":false}");
                        }
                    });
                    return; // answered asynchronously, once the user decided
                }
                case "storage.get": {
                    if (!require("storage", safe)) return;
                    Object v = storage.get(args.optString(0, ""));
                    JSONObject r = new JSONObject();
                    r.put("value", v == null ? JSONObject.NULL : v);
                    safe.respond(r.toString());
                    break;
                }
                case "storage.set": {
                    if (!require("storage", safe)) return;
                    Object value = args.length() > 1 ? args.opt(1) : null;
                    if (value == JSONObject.NULL) value = null;
                    try {
                        storage.set(args.optString(0, ""), value);
                    } catch (ExtensionStorage.QuotaException e) {
                        safe.respond(err("storage: " + e.getMessage()));
                        return;
                    }
                    safe.respond(ok());
                    break;
                }
                case "storage.remove": {
                    if (!require("storage", safe)) return;
                    storage.remove(args.optString(0, ""));
                    safe.respond(ok());
                    break;
                }
                case "storage.keys": {
                    if (!require("storage", safe)) return;
                    JSONObject r = new JSONObject();
                    r.put("keys", storage.keys());
                    safe.respond(r.toString());
                    break;
                }
                case "servers.list": {
                    if (!require("servers.read", safe)) return;
                    JSONArray arr = new JSONArray();
                    for (ServerInstance s : ServerRepo.get(activity).all()) arr.put(serverJson(s));
                    JSONObject r = new JSONObject();
                    r.put("servers", arr);
                    safe.respond(r.toString());
                    break;
                }
                case "servers.get": {
                    if (!require("servers.read", safe)) return;
                    ServerInstance s = ServerRepo.get(activity).byId(args.optString(0, ""));
                    if (s == null) { safe.respond(err("unknown server")); return; }
                    JSONObject r = new JSONObject();
                    r.put("server", serverJson(s));
                    safe.respond(r.toString());
                    break;
                }
                case "console.tail": {
                    if (!require("console.read", safe)) return;
                    String id = args.optString(0, "");
                    int lines = Math.max(1, Math.min(args.optInt(1, 200), MAX_LOG_TAIL));
                    JSONArray out = new JSONArray();
                    KodaServerService svc = service;
                    if (svc != null) {
                        List<String> log = svc.getLog(id);
                        int from = Math.max(0, log.size() - lines);
                        for (int i = from; i < log.size(); i++) out.put(log.get(i));
                    }
                    JSONObject r = new JSONObject();
                    r.put("lines", out);
                    r.put("live", svc != null);
                    safe.respond(r.toString());
                    break;
                }
                default:
                    safe.respond(err("unknown method: " + method));
            }
        } catch (Throwable t) {
            safe.respond(err(String.valueOf(t.getMessage())));
        }
    }

    /** only the whitelisted server fields, never paths or secrets. */
    private JSONObject serverJson(ServerInstance s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", s.getId());
        o.put("name", s.getName());
        o.put("type", s.getType() == null ? "" : s.getType().name());
        o.put("mcVersion", s.getVersion() == null ? "" : s.getVersion());
        o.put("state", s.state == null ? "OFFLINE" : s.state.name());
        o.put("port", s.getPort());
        o.put("address", s.getJoinAddress());
        o.put("ramMB", s.getRamMB());
        o.put("maxPlayers", s.getMaxPlayers());
        o.put("isDatabase", s.isDatabase());
        return o;
    }

    private JSONObject themeJson() throws Exception {
        boolean light = false;
        try {
            light = eu.kodanetwork.mchost.util.ThemeHelper.isLightMode(activity);
        } catch (Throwable ignored) {}
        JSONObject r = new JSONObject();
        r.put("mode", light ? "light" : "dark");
        r.put("orange", "#FF6B00");
        r.put("bg", "#080808");
        r.put("panel", "#12121A");
        r.put("cell", "#1A1A24");
        r.put("card", "#241C18");
        r.put("text", "#F0F0F0");
        r.put("textDim", "#8A8A9A");
        r.put("online", "#69781D");
        return r;
    }

    private boolean require(String permission, Responder safe) {
        if (ext.permissions.contains(permission)) return true;
        safe.respond(err("permission denied: " + permission));
        return false;
    }

    private synchronized boolean rateOk() {
        long now = SystemClock.elapsedRealtime();
        if (now - rateWindowStart >= 1000) {
            rateWindowStart = now;
            rateWindowCount = 0;
        }
        return ++rateWindowCount <= MAX_CALLS_PER_SECOND;
    }

    private static String ok() { return "{\"ok\":true}"; }

    private static String err(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", message == null ? "error" : message);
            return o.toString();
        } catch (Throwable t) {
            return "{\"error\":\"error\"}";
        }
    }
}
