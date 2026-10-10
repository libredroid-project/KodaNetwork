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
// Copyright (c) 2026 KodaHosting
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient, jsonError, isPraetorAdmin } from "../_shared/deviceAuth.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "GET" && req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  try {
    // admin only: this function syncs and deletes koda_servers rows
    let appUuid: string | undefined;
    let deviceToken: string | undefined;
    if (req.method === "POST") {
      const body = await req.json().catch(() => ({}));
      appUuid = body.app_uuid;
      deviceToken = body.device_token;
    } else {
      const url = new URL(req.url);
      appUuid = url.searchParams.get("app_uuid") ?? undefined;
      deviceToken = url.searchParams.get("device_token") ?? undefined;
    }
    const supabaseAdmin = adminClient();
    if (!(await isPraetorAdmin(supabaseAdmin, appUuid, deviceToken))) {
      return jsonError("unauthorized", 401);
    }

    const apiKeyPrefix = Deno.env.get("IONOS_API_PREFIX") ?? "";
    const apiSecret = Deno.env.get("IONOS_API_SECRET") ?? "";
    const fullApiKey = `${apiKeyPrefix}.${apiSecret}`;
    const domain = "kodanetwork.eu";
    
    // 1. get the zone id
    const zonesRes = await fetch("https://api.hosting.ionos.com/dns/v1/zones", {
      headers: { "X-API-Key": fullApiKey }
    });
    
    if (!zonesRes.ok) {
        throw new Error("Failed to fetch zones from IONOS");
    }
    
    const zonesData = await zonesRes.json();
    const zone = (zonesData as any[])?.find((z: any) => z.name === domain);
    if (!zone) throw new Error("Zone kodanetwork.eu not found");
    const zoneId = zone.id;

    // 2. get every record of the zone
    const recordsRes = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}?recordType=A`, {
      headers: { "X-API-Key": fullApiKey }
    });
    
    if (!recordsRes.ok) {
        throw new Error("Failed to fetch records from IONOS");
    }
    
    const recordsData = await recordsRes.json();
    const records = Array.isArray(recordsData.records) ? recordsData.records : [];
    
    // 3. keep the subdomains of kodanetwork.eu
    const servers: { host: string, target: string }[] = [];
    
    for (const record of records) {
        if (record.type === "A" && record.name.endsWith("." + domain)) {
            const subdomain = record.name.replace("." + domain, "");
            // drop system and wildcard records, KodaHosting only creates direct A records
            if (subdomain !== "*" && subdomain !== "@" && subdomain !== "www") {
                servers.push({
                    host: subdomain,
                    target: record.content
                });
            }
        }
    }
    const activeHosts = servers.map(s => s.host);
    
    // 4. clean up servers that vanished from the koda_servers table in supabase
    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    
    if (supabaseUrl && supabaseKey && activeHosts.length > 0) {
      const activeHostsStr = activeHosts.join(",");
      await fetch(`${supabaseUrl}/rest/v1/koda_servers?host=not.in.(${activeHostsStr})`, {
        method: "DELETE",
        headers: {
          "apikey": supabaseKey,
          "Authorization": `Bearer ${supabaseKey}`
        }
      });
    } else if (supabaseUrl && supabaseKey && activeHosts.length === 0) {
      console.warn("IONOS returned 0 active servers. Skipping deletion to prevent data loss.");
    }

    return new Response(JSON.stringify({ ok: true, servers }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });

  } catch (e: any) {
    return new Response(JSON.stringify({ error: "internal_error", detail: e.message }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
