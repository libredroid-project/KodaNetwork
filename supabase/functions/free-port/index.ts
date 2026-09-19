// Copyright (c) 2026 KodaHosting
// Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License
// (see LICENSE, LOPL_v1.0_PREVIEW.md, COMMERCIAL-LICENSE.md)
import { corsHeaders } from "../_shared/cors.ts";
import { validateHost } from "../_shared/validation.ts";
import { adminClient, jsonError, verifyDeviceToken, countPortsForDevice, MAX_PORTS_PER_DEVICE } from "../_shared/deviceAuth.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const { host, type, app_uuid, device_token } = await req.json();

    const supabaseAdmin = adminClient();

    // Geraete-Token verifizieren (Anon-Key allein reicht seit 2026-09-12 nicht mehr)
    if (!(await verifyDeviceToken(supabaseAdmin, app_uuid, device_token))) {
      return jsonError("unauthorized", 401);
    }

    if (!host || !type) {
      return jsonError("missing_parameters", 400);
    }

    // Quota pro Geraet (Port-Erschoepfungs-DoS verhindern)
    const usedByDevice = await countPortsForDevice(supabaseAdmin, app_uuid);
    if (usedByDevice >= MAX_PORTS_PER_DEVICE) {
      return jsonError("port_quota_exceeded", 429);
    }

    const validationError = validateHost(host);
    if (validationError) {
      return jsonError(validationError, 400);
    }

    let minPort = 30000;
    let maxPort = 39999;
    if (type === "bedrock") {
      minPort = 40000;
      maxPort = 49999;
    } else if (type === "voicechat") {
      minPort = 50000;
      maxPort = 59999;
    }

    // Fetch all used ports in this range
    const { data: usedPortsData, error: fetchError } = await supabaseAdmin
      .from('koda_ports')
      .select('port')
      .gte('port', minPort)
      .lte('port', maxPort);

    if (fetchError) {
      throw new Error("Failed to fetch ports: " + fetchError.message);
    }

    const usedPorts = new Set(usedPortsData?.map(row => row.port) || []);

    // Find available ports
    const availablePorts = [];
    for (let p = minPort; p <= maxPort; p++) {
      if (!usedPorts.has(p)) {
        availablePorts.push(p);
      }
    }

    if (availablePorts.length === 0) {
      return jsonError("ports_exhausted", 409);
    }

    // Try picking a random available port up to 5 times (in case of race conditions)
    for (let i = 0; i < 5; i++) {
      const randomIndex = Math.floor(Math.random() * availablePorts.length);
      const port = availablePorts[randomIndex];

      const { error } = await supabaseAdmin
        .from('koda_ports')
        .insert({ port, host });

      if (!error) {
        return new Response(JSON.stringify({ port }), {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (error.code !== "23505") { // not a unique violation
         throw new Error(error.message);
      }
      // If unique violation, try again
    }

    return jsonError("ports_exhausted", 409);

  } catch (err: any) {
    return jsonError(err.message, 500);
  }
});
