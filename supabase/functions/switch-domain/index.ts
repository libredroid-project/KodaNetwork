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
// switch-domain: temporarily moves one server to another base domain when its own
// domain is switched off (app_settings.domain_states), and moves it back when the
// original domain is usable again. The device drives both directions; this function
// owns the DNS records (IONOS) and the koda_servers row so a half finished switch
// can never leave the network pointing at nothing.
//
// POST body:
//   { app_uuid, device_token, host, to_domain, target }   -> create the temp records
//   { app_uuid, device_token, host, restore: true }       -> back to the original
//
// deliberately NOT delete-dns-link: that one frees the host's ports, and the ports
// must survive a domain switch. This function only touches A/SRV records.
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient, jsonError, verifyDeviceToken, canManageHost } from "../_shared/deviceAuth.ts";

function ionosHeaders(): { [k: string]: string } {
  const apiKey = `${Deno.env.get("IONOS_API_PREFIX") ?? ""}.${Deno.env.get("IONOS_API_SECRET") ?? ""}`;
  return { "Content-Type": "application/json", "X-API-Key": apiKey };
}

async function zoneFor(domain: string): Promise<any | null> {
  const res = await fetch("https://api.hosting.ionos.com/dns/v1/zones", { headers: ionosHeaders() });
  if (!res.ok) return null;
  const zones = await res.json();
  return (zones as any[])?.find((z: any) => z.name === domain) ?? null;
}

/** records of one name+type as the api filter returns them */
async function filteredRecords(zoneId: string, name: string, type: string): Promise<any[]> {
  try {
    const res = await fetch(
      `https://api.hosting.ionos.com/dns/v1/zones/${zoneId}?recordName=${encodeURIComponent(name)}&recordType=${type}`,
      { headers: ionosHeaders() },
    );
    if (!res.ok) return [];
    const data = await res.json();
    return data?.records ?? [];
  } catch (_e) {
    return [];
  }
}

/** removes every A/CNAME/SRV record for the fqdn and its service names (idempotent). */
async function deleteRecords(zoneId: string, fqdn: string) {
  const names = [fqdn, `_minecraft._tcp.${fqdn}`, `_voicechat._udp.${fqdn}`];
  const seen = new Set<string>();
  for (const rt of ["A", "CNAME", "SRV"]) {
    for (const n of names) {
      for (const rec of await filteredRecords(zoneId, n, rt)) {
        if (!rec?.id || seen.has(rec.id)) continue;
        seen.add(rec.id);
        try {
          await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}/records/${rec.id}`, {
            method: "DELETE",
            headers: ionosHeaders(),
          });
        } catch (_e) { /* already gone */ }
      }
    }
  }
}

/** the java port from the server's own SRV record. hosts from transfers,
 *  reinstalls and older rows have no koda_ports entry, their SRV is the truth. */
async function srvJavaPort(zoneId: string, fqdn: string): Promise<number> {
  const recs = await filteredRecords(zoneId, `_minecraft._tcp.${fqdn}`, "SRV");
  const rec = recs.find((r) => String(r?.name ?? "") === `_minecraft._tcp.${fqdn}`) ?? recs[0];
  if (!rec || typeof rec.content !== "string") return 0;
  const port = Number(rec.content.trim().split(/\s+/)[1]);
  return Number.isInteger(port) && port > 0 && port < 65536 ? port : 0;
}

async function createRecords(zoneId: string, entries: any[]) {
  const res = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}/records`, {
    method: "POST",
    headers: ionosHeaders(),
    body: JSON.stringify(entries),
  });
  if (!res.ok) throw new Error("record creation failed: " + await res.text());
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  try {
    const body = await req.json();
    const supabaseAdmin = adminClient();
    const host = String(body.host ?? "").toLowerCase();
    if (!host) return jsonError("missing_host", 400);

    if (!(await verifyDeviceToken(supabaseAdmin, body.app_uuid, body.device_token))) {
      return jsonError("unauthorized", 401);
    }
    if (!(await canManageHost(supabaseAdmin, body.app_uuid, body.device_token, host))) {
      return jsonError("unauthorized", 401);
    }

    const { data: server } = await supabaseAdmin
      .from("koda_servers")
      .select("host,base_domain,domain_switched_from")
      .eq("host", host)
      .maybeSingle();
    if (!server) return jsonError("server_not_found", 404);
    const currentDomain = String((server as any).base_domain || "kodanetwork.eu");
    const switchedFrom = (server as any).domain_switched_from as string | null;

    // ------------------------------------------------------------- restore
    if (body.restore === true) {
      if (!switchedFrom) return jsonError("not_switched", 409);
      // the TEMP records live in the zone of the domain we are currently on
      const tempZone = await zoneFor(currentDomain);
      if (!tempZone) return jsonError("zone_not_found", 500);
      // the temp records go, the original ones were never touched
      await deleteRecords(tempZone.id, `${host}.${currentDomain}`);

      const { error } = await supabaseAdmin
        .from("koda_servers")
        .update({ base_domain: switchedFrom, domain_switched_from: null })
        .eq("host", host);
      if (error) return jsonError("db_update_failed", 500);
      return new Response(JSON.stringify({ ok: true, restored: true, base_domain: switchedFrom }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ------------------------------------------------------------- switch
    const toDomain = String(body.to_domain ?? "").toLowerCase();
    const target = String(body.target ?? "");
    if (!toDomain || !target) return jsonError("missing_parameters", 400);
    if (toDomain === currentDomain) return jsonError("already_on_domain", 409);
    if (!/^[0-9]{1,3}(\.[0-9]{1,3}){3}$/.test(target)) return jsonError("invalid_target", 400);

    // the target domain must be usable (active or no_new, both keep serving)
    const { data: setting } = await supabaseAdmin
      .from("app_settings").select("value").eq("key", "domain_states").maybeSingle();
    const modes = (setting as any)?.value ?? {};
    if (modes[toDomain] === "off") return jsonError("target_domain_off", 409);

    // the java port, in order of truth: the live SRV of the current domain (the
    // address that works today), then the app's own value, then koda_ports. the
    // table alone is not enough: transferred, reinstalled and older servers never
    // got a row there, and that made the switch fail with no_java_port.
    const { data: ports } = await supabaseAdmin.from("koda_ports").select("port").eq("host", host);
    const portList = ((ports ?? []) as any[]).map((r) => Number(r.port));
    let javaPort = 0;
    const ownZone = await zoneFor(currentDomain);
    if (ownZone) javaPort = await srvJavaPort(ownZone.id, `${host}.${currentDomain}`);
    const bodyPort = Number(body.port ?? 0);
    if (!javaPort && Number.isInteger(bodyPort) && bodyPort > 0 && bodyPort < 65536) javaPort = bodyPort;
    if (!javaPort) javaPort = portList.find((p) => p > 0 && p < 40000) ?? 0;
    const bodyVcPort = Number(body.voicechat_port ?? 0);
    const voicechatPort = Number.isInteger(bodyVcPort) && bodyVcPort > 0 && bodyVcPort < 65536
      ? bodyVcPort
      : (portList.find((p) => p >= 50000 && p < 60000) ?? 0);
    if (!javaPort) return jsonError("no_java_port", 409);

    const zone = await zoneFor(toDomain);
    if (!zone) return jsonError("zone_not_found", 500);

    const fqdn = `${host}.${toDomain}`;
    await deleteRecords(zone.id, fqdn);
    const records: any[] = [{ name: fqdn, type: "A", content: target, ttl: 3600, disabled: false }];
    records.push({ name: `_minecraft._tcp.${fqdn}`, type: "SRV", content: `0 ${javaPort} ${fqdn}.`, ttl: 3600, prio: 0, disabled: false });
    if (voicechatPort) {
      records.push({ name: `_voicechat._udp.${fqdn}`, type: "SRV", content: `0 ${voicechatPort} ${fqdn}.`, ttl: 3600, prio: 0, disabled: false });
    }
    await createRecords(zone.id, records);

    const { error } = await supabaseAdmin
      .from("koda_servers")
      .update({ base_domain: toDomain, domain_switched_from: switchedFrom ?? currentDomain })
      .eq("host", host);
    if (error) return jsonError("db_update_failed", 500);

    return new Response(JSON.stringify({ ok: true, base_domain: toDomain, switched_from: switchedFrom ?? currentDomain }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    return jsonError("internal_error: " + e.message, 500);
  }
});
