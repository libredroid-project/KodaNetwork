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
package eu.kodanetwork.mchost.transfer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * the shared model of one device transfer: the RPC row (pairing code, LAN
 * endpoint, keys) plus the manifest the sender serves over HTTP. one entry per
 * server, carrying the sender's full ServerInstance JSON, sizes, hash and IV.
 */
public final class TransferSession {

    public String code;
    public String lanIp;               // comma separated list, receiver tries each
    public int lanPort;
    public String httpToken;
    public String aesKeyHex;
    public String targetAppUuid;       // receiver chosen by the sender, may be null
    public String fromAppUuid;         // sender identity for the P.R.A.E.T.O.R. screen
    public String fromNickname = "";
    public String fromDeviceModel = "";
    public final List<Pack> packs = new ArrayList<>();

    /** display name of a device: nickname, then model, then the uuid (user rule). */
    public static String deviceLabel(String nickname, String model, String appUuid) {
        if (nickname != null && !nickname.trim().isEmpty()) return nickname.trim();
        if (model != null && !model.trim().isEmpty()) return model.trim();
        return appUuid == null ? "?" : appUuid;
    }

    /** every address the sender announced, in order. */
    public List<String> lanIpList() {
        List<String> out = new ArrayList<>();
        if (lanIp != null) {
            for (String part : lanIp.split(",")) {
                String ip = part.trim();
                if (!ip.isEmpty() && !out.contains(ip)) out.add(ip);
            }
        }
        return out;
    }

    /** one server inside a transfer. */
    public static class Pack {
        public String serverId;     // local server id on the SENDER device
        public String host;         // cloud host / subdomain, the adoption key
        public String name;
        public String version;
        public String sha256;       // hash of the PLAINTEXT zip
        public String ivHex;        // AES-GCM IV of this blob (key is per session)
        public long encSize;        // encrypted blob size in bytes
        public JSONObject instance; // full ServerInstance JSON from the sender
    }

    /** the manifest served at GET /m, ciphertext metadata only, no key material. */
    public JSONObject manifestJson() {
        try {
            JSONArray list = new JSONArray();
            for (Pack p : packs) {
                JSONObject o = new JSONObject();
                o.put("serverId", p.serverId);
                o.put("host", p.host);
                o.put("name", p.name);
                o.put("version", p.version);
                o.put("size", p.encSize);
                o.put("sha256", p.sha256);
                o.put("iv", p.ivHex);
                o.put("instance", p.instance);
                list.put(o);
            }
            return new JSONObject().put("servers", list);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static TransferSession fromManifest(JSONObject manifest) {
        TransferSession s = new TransferSession();
        JSONArray list = manifest.optJSONArray("servers");
        if (list == null) return s;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Pack p = new Pack();
            p.serverId = o.optString("serverId", "");
            p.host = o.optString("host", "");
            p.name = o.optString("name", p.host);
            p.version = o.optString("version", "");
            p.sha256 = o.optString("sha256", "");
            p.ivHex = o.optString("iv", "");
            p.encSize = o.optLong("size", 0);
            p.instance = o.optJSONObject("instance");
            if (!p.serverId.isEmpty() && !p.host.isEmpty()) s.packs.add(p);
        }
        return s;
    }

    /** hosts for the DB payload, only host names travel through the RPC row. */
    public JSONArray hostsJson() {
        JSONArray out = new JSONArray();
        for (Pack p : packs) out.put(p.host);
        return out;
    }

    public long totalEncSize() {
        long total = 0;
        for (Pack p : packs) total += p.encSize;
        return total;
    }

    /**
     * an RPC call with the anon key (house rule: writes authenticate through the
     * device token parameters, never through a session JWT).
     */
    public static String callRpc(String path, String body) throws IOException {
        String base = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl();
        String key = eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("apikey", key);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        Scanner sc = new Scanner(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream())
                .useDelimiter("\\A");
        return sc.hasNext() ? sc.next() : "";
    }
}
