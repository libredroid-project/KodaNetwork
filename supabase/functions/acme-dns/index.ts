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
// acme-dns: the DNS-01 hook for the wildcard certificates. The tunnel server is
// allowed to set and remove ONE kind of record - the _acme-challenge TXT of the
// two KodaHosting zones - so the IONOS keys never have to leave this function's
// environment. Everything else is rejected: fixed record type, fixed name
// prefix, token protected, rate limited per caller.
//
// POST { token, action: "present" | "cleanup", fqdn, value }
//   present -> creates _acme-challenge.<domain> TXT with the ACME value
//   cleanup -> removes every TXT with that exact name
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient } from "../_shared/deviceAuth.ts";

const ZONES = ["kodanetwork.eu", "kodaserv.eu"];
const TXT_PREFIX = "_acme-challenge.";
const MAX_PER_MINUTE = 30;

function ionosHeaders(): { [k: string]: string } {
  const apiKey = `${Deno.env.get("IONOS_API_PREFIX") ?? ""}.${Deno.env.get("IONOS_API_SECRET") ?? ""}`;
  return { "Content-Type": "application/json", "X-API-Key": apiKey };
}

function fail(message: string, status: number) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function zoneFor(domain: string): Promise<any | null> {
  const res = await fetch("https://api.hosting.ionos.com/dns/v1/zones", { headers: ionosHeaders() });
  if (!res.ok) return null;
  const zones = await res.json();
  return (zones as any[])?.find((z) => z.name === domain) ?? null;
}

async function txtRecords(zoneId: string, fqdn: string): Promise<any[]> {
  try {
    const res = await fetch(
      `https://api.hosting.ionos.com/dns/v1/zones/${zoneId}?recordName=${encodeURIComponent(fqdn)}&recordType=TXT`,
      { headers: ionosHeaders() },
    );
    if (!res.ok) return [];
    const data = await res.json();
    return (data?.records ?? []).filter((r: any) => String(r?.name ?? "").toLowerCase() === fqdn);
  } catch (_e) {
    return [];
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  try {
    const body = await req.json();
    const expected = Deno.env.get("ACME_HOOK_TOKEN") ?? "";
    if (!expected || String(body.token ?? "") !== expected) return fail("unauthorized", 401);

    const action = String(body.action ?? "").toLowerCase();
    if (action !== "present" && action !== "cleanup") return fail("unknown_action", 400);

    const fqdn = String(body.fqdn ?? "").trim().toLowerCase().replace(/\.$/, "");
    if (!fqdn.startsWith(TXT_PREFIX)) return fail("bad_name", 400);
    const zoneName = ZONES.find((z) => fqdn.endsWith("." + z));
    if (!zoneName) return fail("bad_zone", 400);
    // only the apex itself: _acme-challenge.kodanetwork.eu (a wildcard request),
    // never deeper names
    if (fqdn !== TXT_PREFIX + zoneName) return fail("bad_name", 400);

    const value = String(body.value ?? "");
    if (action === "present" && !value) return fail("missing_value", 400);

    const supabaseAdmin = adminClient();
    try {
      const { data } = await supabaseAdmin.rpc("fn_api_rate_take", {
        p_key: "acme_dns",
        p_max: MAX_PER_MINUTE,
        p_window_seconds: 60,
      });
      const retry = Number(data ?? 0) || 0;
      if (retry > 0) {
        return new Response(JSON.stringify({ error: "rate_limited", retry_after: retry }), {
          status: 429,
          headers: { ...corsHeaders, "Content-Type": "application/json", "Retry-After": String(retry) },
        });
      }
    } catch (_e) { /* a missing limiter must not block a renewal */ }

    const zone = await zoneFor(zoneName);
    if (!zone) return fail("zone_not_found", 500);

    if (action === "present") {
      const res = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zone.id}/records`, {
        method: "POST",
        headers: ionosHeaders(),
        body: JSON.stringify([{ name: fqdn, type: "TXT", content: value, ttl: 60, disabled: false }]),
      });
      if (!res.ok) return fail("record_create_failed: " + (await res.text()).slice(0, 200), 500);
      return new Response(JSON.stringify({ ok: true, fqdn }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // cleanup: with a value only that exact record (apex and wildcard share one
    // name, so a blind delete could pull the ground from under the second
    // challenge); without a value every TXT with that name
    const records = await txtRecords(zone.id, fqdn);
    let removed = 0;
    for (const rec of records) {
      const content = String(rec.content ?? "").replace(/^"|"$/g, "");
      if (value && content !== value) continue;
      try {
        await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zone.id}/records/${rec.id}`, {
          method: "DELETE",
          headers: ionosHeaders(),
        });
        removed++;
      } catch (_e) { /* already gone */ }
    }
    return new Response(JSON.stringify({ ok: true, removed }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    return fail("internal_error: " + e.message, 500);
  }
});
