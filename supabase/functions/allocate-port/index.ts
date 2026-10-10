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
import { validateHost } from "../_shared/validation.ts";
import { adminClient, jsonError, verifyDeviceToken, countPortsForDevice, MAX_PORTS_PER_DEVICE } from "../_shared/deviceAuth.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const { host, type, app_uuid, device_token } = await req.json();

    const supabaseAdmin = adminClient();

    // check the device token (the anon key alone stopped being enough on 2026-09-12)
    if (!(await verifyDeviceToken(supabaseAdmin, app_uuid, device_token))) {
      return jsonError("unauthorized", 401);
    }

    if (!host || !type) {
      return jsonError("missing_parameters", 400);
    }

    // per-device quota, so nobody can exhaust the port range
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
      // the firewall only opens 55000-65000 (TCP 30000-40000 java, UDP 40000-50000 bedrock)
      minPort = 55000;
      maxPort = 59999;
    }

    // fetch all the used ports in this range
    const { data: usedPortsData, error: fetchError } = await supabaseAdmin
      .from('koda_ports')
      .select('port')
      .gte('port', minPort)
      .lte('port', maxPort);

    if (fetchError) {
      throw new Error("Failed to fetch ports: " + fetchError.message);
    }

    const usedPorts = new Set(usedPortsData?.map(row => row.port) || []);

    // find the free ones
    const availablePorts = [];
    for (let p = minPort; p <= maxPort; p++) {
      if (!usedPorts.has(p)) {
        availablePorts.push(p);
      }
    }

    if (availablePorts.length === 0) {
      return jsonError("ports_exhausted", 409);
    }

    // pick a random free port, up to 5 tries in case of race conditions
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
      // a unique violation means someone was faster, try again
    }

    return jsonError("ports_exhausted", 409);

  } catch (err: any) {
    return jsonError(err.message, 500);
  }
});
