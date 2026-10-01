package eu.kodanetwork.mchost.utils;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import android.content.Context;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.network.supabase.SupabaseAuth;
import eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient;
import java.io.File;

public class HibernationManager {

    public static void hibernateServer(Context context, ServerInstance server, ServerRepo repo) throws Exception {
        if (server.state == ServerInstance.State.HIBERNATED) return;

        // 1. Delete DNS entries if any — one retry, then continue (hibernate must not block)
        if (server.getSubdomain() != null && !server.getSubdomain().isEmpty()) {
            String token = SupabaseAuth.getSessionToken(context);
            boolean dnsDeleted = false;
            for (int attempt = 0; attempt < 2 && !dnsDeleted; attempt++) {
                try {
                    new SupabaseFunctionsClient(context).deleteDnsLink(token, server.getSubdomain(), server.getBaseDomain());
                    dnsDeleted = true;
                } catch (Exception e) {
                    android.util.Log.w("HibernationManager",
                            "DNS delete failed (attempt " + (attempt + 1) + "): " + e.getMessage());
                }
            }
            if (!dnsDeleted) {
                android.util.Log.e("HibernationManager",
                        "DNS for " + server.getSubdomain() + " could not be deleted; continuing hibernate anyway");
            }
        }

        // 2. Patch Supabase: HIBERNATED status (always, independent of DNS result)
        patchServerById(context, server, new org.json.JSONObject()
                .put("server_version", "HIBERNATED")
                .put("online_players", 0));

        // 3. Zip the server directory
        File serverDir = new File(server.getServerDir());
        File zipFile = new File(serverDir.getParentFile(), server.getId() + "_hibernated.zip");
        if (serverDir.exists()) {
            ZipUtils.zipFolder(serverDir.getAbsolutePath(), zipFile.getAbsolutePath());
            ZipUtils.deleteDirectory(serverDir);
        }

        // 4. Update local state
        server.state = ServerInstance.State.HIBERNATED;
        server.setDomainLink("");
        repo.update(server);
    }

    /**
     * Checks whether the koda_servers row for this host still belongs to this device.
     * If it does, the DNS "occupation" is our own entry and waking up is fine.
     */
    private static boolean dnsBelongsToUs(Context context, ServerInstance server) {
        try {
            String appUuid = eu.kodanetwork.mchost.App.getPrefs(context).getString("app_uuid", "");
            String deviceToken = eu.kodanetwork.mchost.App.getPrefs(context).getString("device_token", "");
            if (appUuid.isEmpty() || deviceToken.isEmpty()) return false;

            org.json.JSONObject body = new org.json.JSONObject()
                    .put("p_app_uuid", appUuid)
                    .put("p_device_token", deviceToken);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
                    eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                            + "/rest/v1/rpc/rpc_get_my_servers").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
            conn.setRequestProperty("Authorization", "Bearer " + eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey());
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
            if (conn.getResponseCode() != 200) return false;
            java.util.Scanner sc = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
            org.json.JSONArray arr = new org.json.JSONArray(sc.hasNext() ? sc.next() : "[]");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject row = arr.getJSONObject(i);
                if (server.getSubdomain().equals(row.optString("host", ""))) {
                    return true; // our own row -> DNS is ours
                }
            }
        } catch (Exception e) {
            android.util.Log.w("HibernationManager", "dnsBelongsToUs check failed: " + e.getMessage());
        }
        return false;
    }

    public static void wakeUpServer(Context context, ServerInstance server, ServerRepo repo) throws Exception {
        if (server.state != ServerInstance.State.HIBERNATED) return;

        // 1. Check if DNS is occupied - but only by SOMEONE ELSE.
        // A hibernated server keeps its DNS entry, so "taken" is expected for our own
        // name. We only block when the record belongs to a different owner.
        if (server.getSubdomain() != null && !server.getSubdomain().isEmpty()) {
            String token = SupabaseAuth.getSessionToken(context);
            try {
                boolean isTaken = new SupabaseFunctionsClient(context)
                        .checkServerName(token, server.getSubdomain(), server.getBaseDomain());
                if (isTaken && !dnsBelongsToUs(context, server)) {
                    throw new RuntimeException("DNS_OCCUPIED");
                }
            } catch (Exception e) {
                if ("DNS_OCCUPIED".equals(e.getMessage())) throw e;
                android.util.Log.e("HibernationManager", "Failed to check DNS", e);
            }
        }

        // serverDir kann bei Cloud-Platzhaltern null sein -> auf kanonischen Pfad fallen
        if (server.getServerDir() == null) {
            File canonical = new File(new File(context.getFilesDir(), "servers"), server.getId());
            server.setServerDir(canonical.getAbsolutePath());
        }

        File zipFile = new File(new File(server.getServerDir()).getParentFile(), server.getId() + "_hibernated.zip");
        if (zipFile.exists()) {
            ZipUtils.unzip(zipFile.getAbsolutePath(), new File(server.getServerDir()).getParent());
            zipFile.delete();
        } else {
            new File(server.getServerDir()).mkdirs();
        }

        server.state = ServerInstance.State.OFFLINE;
        server.setLastActive(System.currentTimeMillis());
        repo.update(server);

        // 2. Clear HIBERNATED status in Supabase so the app doesn't re-hibernate it
        String version = server.getVersion();
        if (version == null || version.trim().isEmpty()) version = "1.21.11";
        patchServerById(context, server, new org.json.JSONObject()
                .put("server_version", version));
    }

    /** Fire-and-forget rpc_patch_server_by_id with proper auth and JSON body. */
    private static void patchServerById(Context context, ServerInstance server, org.json.JSONObject payload) {
        try {
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("p_app_uuid", eu.kodanetwork.mchost.App.getPrefs(context).getString("app_uuid", ""))
                    .put("p_device_token", eu.kodanetwork.mchost.App.getPrefs(context).getString("device_token", ""))
                    .put("p_id", server.getId())
                    .put("p_payload", payload);
            String url = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                    + "/rest/v1/rpc/rpc_patch_server_by_id";
            okhttp3.RequestBody reqBody = okhttp3.RequestBody.create(
                    body.toString(), okhttp3.MediaType.parse("application/json"));
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .post(reqBody)
                    .addHeader("apikey", eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey())
                    .addHeader("Authorization", "Bearer " + SupabaseAuth.getSessionToken(context))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build();
            new okhttp3.OkHttpClient().newCall(request).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    android.util.Log.e("HibernationManager", "patchServerById failed", e);
                }
                @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                    if (!response.isSuccessful()) {
                        android.util.Log.w("HibernationManager",
                                "patchServerById HTTP " + response.code() + " for " + server.getId());
                    }
                    response.close();
                }
            });
        } catch (Exception e) {
            android.util.Log.e("HibernationManager", "patchServerById error", e);
        }
    }
}
