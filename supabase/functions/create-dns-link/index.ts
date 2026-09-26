// Copyright (c) 2026 KodaHosting
// Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License
// (see LICENSE, LOPL_v1.0_PREVIEW.md, COMMERCIAL-LICENSE.md)

import { corsHeaders } from "../_shared/cors.ts";
import { validateHost } from "../_shared/validation.ts";
import { adminClient, jsonError, canManageHost } from "../_shared/deviceAuth.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  try {
    const body = await req.json();
    const host = String(body.host ?? "").toLowerCase();
    const target = String(body.target ?? "").toLowerCase();
    const port = Number(body.port ?? 0);

    const supabaseAdmin = adminClient();

    if (!(await canManageHost(supabaseAdmin, body.app_uuid, body.device_token, host))) {
      return jsonError("unauthorized", 401);
    }

    if (!host || !target || !port) return jsonError("missing_parameters", 400);
    const validationError = validateHost(host);
    if (validationError) return jsonError(validationError, 400);

    const apiKeyPrefix = Deno.env.get("IONOS_API_PREFIX") ?? "";
    const apiSecret = Deno.env.get("IONOS_API_SECRET") ?? "";
    const fullApiKey = `${apiKeyPrefix}.${apiSecret}`;
    const domain = String(body.base_domain || "kodanetwork.eu").toLowerCase();
    const ionosHeaders = { "Content-Type": "application/json", "X-API-Key": fullApiKey };

    // 1. Zone finden
    const zonesRes = await fetch("https://api.hosting.ionos.com/dns/v1/zones", { headers: { "X-API-Key": fullApiKey } });
    const zonesData = await zonesRes.json();
    const zone = (zonesData as any[])?.find((z: any) => z.name === domain);
    if (!zone) throw new Error(`Zone ${domain} not found`);
    const zoneId = zone.id;

    const service = String(body.service ?? "_minecraft").toLowerCase();
    const type = String(body.type ?? "tcp").toLowerCase();
    const fqdn = `${host}.${domain}`;
    const srvFqdn = `${service}._${type}.${fqdn}`;

    // 2. Bestehende Records fuer dieses fqdn LOESCHEN (idempotent, keine Duplikate)
    const recordTypes = ["A", "CNAME", "SRV"];
    for (const rt of recordTypes) {
      try {
        const existingRes = await fetch(
          `https://api.hosting.ionos.com/dns/v1/zones/${zoneId}?recordName=${encodeURIComponent(fqdn)}&recordType=${rt}`,
          { headers: { "X-API-Key": fullApiKey } }
        );
        if (existingRes.ok) {
          const existingData = await existingRes.json();
          if (existingData?.records?.length > 0) {
            for (const rec of existingData.records) {
              await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}/records/${rec.id}`, {
                method: "DELETE",
                headers: { "X-API-Key": fullApiKey }
              });
            }
          }
        }
      } catch (e) {
        // Beim Loeschen auftretende Fehler ignorieren (Record existiert evtl. nicht)
      }
    }
    // Auch alte SRV-Records fuer die Service-Variante loeschen
    try {
      const srvExisting = await fetch(
        `https://api.hosting.ionos.com/dns/v1/zones/${zoneId}?recordName=${encodeURIComponent(srvFqdn)}&recordType=SRV`,
        { headers: { "X-API-Key": fullApiKey } }
      );
      if (srvExisting.ok) {
        const srvData = await srvExisting.json();
        if (srvData?.records?.length > 0) {
          for (const rec of srvData.records) {
            await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}/records/${rec.id}`, {
              method: "DELETE",
              headers: { "X-API-Key": fullApiKey }
            });
          }
        }
      }
    } catch (e) { /* ignore */ }

    // 3. A-Record erstellen (zeigt auf VPS IP)
    const createARes = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}/records`, {
      method: "POST",
      headers: ionosHeaders,
      body: JSON.stringify([{ name: fqdn, type: "A", content: target, ttl: 3600, disabled: false }])
    });
    if (!createARes.ok) {
      throw new Error("A-Record creation failed: " + await createARes.text());
    }

    // 4. SRV-Record erstellen
    const createSrvRes = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}/records`, {
      method: "POST",
      headers: ionosHeaders,
      body: JSON.stringify([{ name: srvFqdn, type: "SRV", content: `0 ${port} ${fqdn}.`, ttl: 3600, prio: 0, disabled: false }])
    });
    if (!createSrvRes.ok) {
      // Rollback: A-Record wieder loeschen
      try {
        const aRecordRes = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}?recordName=${encodeURIComponent(fqdn)}&recordType=A`, { headers: { "X-API-Key": fullApiKey } });
        if (aRecordRes.ok) {
          const aData = await aRecordRes.json();
          if (aData?.records?.length > 0) {
            await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zoneId}/records/${aData.records[0].id}`, { method: "DELETE", headers: { "X-API-Key": fullApiKey } });
          }
        }
      } catch (e) { /* ignore */ }
      throw new Error("SRV creation failed: " + await createSrvRes.text());
    }

    return new Response(JSON.stringify({ ok: true, host, vps: target, port }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });

  } catch (e: any) {
    return jsonError("internal_error: " + e.message, 500);
  }
});
