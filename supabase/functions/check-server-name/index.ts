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
// checks whether a subdomain is still free. it talks to the IONOS API with the
// operator's key, so it used to be a free oracle (zone enumeration) and an easy
// way to burn the API quota for anyone holding the anon key. a verified device
// token is required now.
import { corsHeaders } from "../_shared/cors.ts";
import { validateHost } from "../_shared/validation.ts";
import { adminClient, jsonError, verifyDeviceToken } from "../_shared/deviceAuth.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  const authHeader = req.headers.get("Authorization")?.replace("Bearer ", "");
  const apikey = req.headers.get("apikey");
  if (!authHeader || !apikey) {
    return jsonError("unauthorized", 401);
  }

  try {
    const body = await req.json();

    const supabaseAdmin = adminClient();
    const { app_uuid, device_token } = body;
    if (!(await verifyDeviceToken(supabaseAdmin, app_uuid, device_token))) {
      return jsonError("unauthorized", 401);
    }

    const host = String(body.host ?? "").toLowerCase();

    if (!host) {
      return jsonError("missing_parameters", 400);
    }

    const validationError = validateHost(host);
    if (validationError) {
      return jsonError(validationError, 400);
    }

    const apiKeyPrefix = Deno.env.get("IONOS_API_PREFIX") ?? "";
    const apiSecret = Deno.env.get("IONOS_API_SECRET") ?? "";
    const fullApiKey = `${apiKeyPrefix}.${apiSecret}`;
    const domain = String(body.base_domain || "kodanetwork.eu").toLowerCase();

    // 1. get the zone id
    const zonesRes = await fetch("https://api.hosting.ionos.com/dns/v1/zones", {
      headers: { "X-API-Key": fullApiKey },
    });
    const zonesData = await zonesRes.json();
    const zone = (zonesData as any[])?.find((z: any) => z.name === domain);
    if (!zone) throw new Error(`Zone ${domain} not found`);
    const zoneId = zone.id;

    const fqdn = `${host}.${domain}`;

    // 2. is there already an A record for this subdomain?
    const res = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}?recordName=${fqdn}&recordType=A`, {
      headers: { "X-API-Key": fullApiKey },
    });

    let isTaken = false;
    if (res.ok) {
      const existing = await res.json();
      const records = Array.isArray(existing.records) ? existing.records : [];
      if (records.length > 0) {
        isTaken = true;
      }
    }

    return new Response(JSON.stringify({
      status: isTaken ? "taken" : "allowed",
      host,
    }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    return jsonError(e.message, 500);
  }
});
