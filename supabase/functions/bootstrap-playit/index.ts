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
// the playit agent token is shared tunnel infrastructure. it used to go out to
// anyone who sent the public anon key as a Bearer header (the check only looked
// at whether the headers existed), so a verified device token is required now.
import { corsHeaders } from "../_shared/cors.ts";
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

    const playitToken = Deno.env.get("PLAYIT_AGENT_TOKEN") ?? "";
    const serverId = String(body.serverId ?? "");
    const port = Number(body.port ?? 25565);
    if (!serverId) {
      return jsonError("missing_server_id", 400);
    }

    return new Response(
      JSON.stringify({
        serverId,
        port,
        token: playitToken,
        mode: "agent_token_bootstrap",
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } },
    );
  } catch (e) {
    return jsonError(String(e), 500);
  }
});
