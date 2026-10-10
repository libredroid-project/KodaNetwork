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
package eu.kodanetwork.mchost.utils;

import android.content.Context;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.network.supabase.SupabaseAuth;
import eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient;
import java.io.File;

public class HibernationManager {

    public static void hibernateServer(Context context, ServerInstance server, ServerRepo repo) throws Exception {
        if (server.state == ServerInstance.State.HIBERNATED) return;

        // 1. drop the DNS entries, one retry and then keep going, hibernate must not block
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

        // 2. patch Supabase to HIBERNATED, always, whatever the DNS call did
        patchServerById(context, server, new org.json.JSONObject()
                .put("server_version", "HIBERNATED")
                .put("online_players", 0));

        // 3. zip the server folder away
        File serverDir = new File(server.getServerDir());
        File zipFile = new File(serverDir.getParentFile(), server.getId() + "_hibernated.zip");
        if (serverDir.exists()) {
            ZipUtils.zipFolder(serverDir.getAbsolutePath(), zipFile.getAbsolutePath());
            ZipUtils.deleteDirectory(serverDir);
        }

        // 4. and bring the local state in line
        server.state = ServerInstance.State.HIBERNATED;
        server.setDomainLink("");
        repo.update(server);
    }


    /**
     * looks at the koda_servers table (not DNS): does another server use this host
     * and is it active (not hibernated, not deleted)? only that is a real conflict,
     * the 180-day auto-cleanup may have freed the name in the meantime.
     */
    private static boolean hostTakenByActiveServer(Context context, ServerInstance server) {
        try {
            String appUuid = eu.kodanetwork.mchost.App.getPrefs(context).getString("app_uuid", "");
            String deviceToken = eu.kodanetwork.mchost.App.getPrefs(context).getString("device_token", "");
            if (appUuid.isEmpty() || deviceToken.isEmpty()) return false;

            org.json.JSONObject body = new org.json.JSONObject()
                    .put("p_app_uuid", appUuid)
                    .put("p_device_token", deviceToken);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
                    eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl()
                            + "/rest/v1/rpc/rpc_get_servers_by_auth_id").openConnection();
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
                String host = row.optString("host", "");
                String ver = row.optString("server_version", "");
                if (!server.getSubdomain().equals(host)) continue;       // different name
                if (host.startsWith("deleted_") || "DELETED".equals(ver)) continue; // dead row
                if ("HIBERNATED".equals(ver)) continue;                   // our own hibernated entry
                if (ver.isEmpty() || ver.startsWith("OFFLINE")) continue; // our own offline entry
                // a real active server is sitting on this host
                return true;
            }
        } catch (Exception e) {
            android.util.Log.w("HibernationManager", "hostTaken check failed: " + e.getMessage());
        }
        return false;
    }

    public static void wakeUpServer(Context context, ServerInstance server, ServerRepo repo) throws Exception {
        if (server.state != ServerInstance.State.HIBERNATED) return;



        // DNS records are NOT the check here, our own hibernated entry always shows
        // as "taken". the DATABASE is: is another ACTIVE server (not hibernated, not
        // deleted) on this host? only that blocks the wake-up, it means the 180-day
        // auto-cleanup freed the name and someone else grabbed it.
        if (server.getSubdomain() != null && !server.getSubdomain().isEmpty()) {
            if (hostTakenByActiveServer(context, server)) {
                throw new RuntimeException("DNS_OCCUPIED");
            }
        }

        // serverDir can be null for cloud placeholders, so fall back to the canonical path
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

        // 2. clear the HIBERNATED status in Supabase, otherwise the app hibernates it again
        String version = server.getVersion();
        if (version == null || version.trim().isEmpty()) version = "1.21.11";
        patchServerById(context, server, new org.json.JSONObject()
                .put("server_version", version));
    }

    /** fire-and-forget rpc_patch_server_by_id with the right auth and a JSON body. */
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
