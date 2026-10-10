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
// "freeing" a port. this file was a byte-for-byte copy of allocate-port, so the
// endpoint actually ALLOCATED a new port instead of releasing one and the rows
// piled up. it deletes the host's rows in the matching band now.
import { corsHeaders } from "../_shared/cors.ts";
import { validateHost } from "../_shared/validation.ts";
import { adminClient, jsonError, verifyDeviceToken, canManageHost } from "../_shared/deviceAuth.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const { host, type, app_uuid, device_token } = await req.json();

    const supabaseAdmin = adminClient();

    // check the device token (the anon key alone stopped being enough on 2026-09-12)
    if (!(await verifyDeviceToken(supabaseAdmin, app_uuid, device_token))) {
      return jsonError("unauthorized", 401);
    }

    if (!host) {
      return jsonError("missing_parameters", 400);
    }

    // only the owner of the host may free its ports
    if (!(await canManageHost(supabaseAdmin, app_uuid, device_token, host))) {
      return jsonError("forbidden", 403);
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

    const { data: deleted, error } = await supabaseAdmin
      .from("koda_ports")
      .delete()
      .eq("host", host)
      .gte("port", minPort)
      .lte("port", maxPort)
      .select("port");

    if (error) {
      throw new Error(error.message);
    }

    const ports = (deleted ?? []).map((row: { port: number }) => row.port);
    return new Response(JSON.stringify({ freed: ports.length, ports }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err: any) {
    return jsonError(err.message, 500);
  }
});
