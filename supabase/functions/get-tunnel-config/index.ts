# Copyright (c) 2026 KodaHosting
# Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License
# (see LICENSE, LOPL_v1.0_PREVIEW.md, COMMERCIAL-LICENSE.md)
// Liefert die Tunnel-Verbindungsdaten (frp-Token) NUR an verifizierte Geraete.
// Damit ist der Token nicht mehr in der APK eingebettet (Security-Fix 2026-09-15).
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient, jsonError, verifyDeviceToken } from "../_shared/deviceAuth.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const { app_uuid, device_token } = await req.json();
    const supabaseAdmin = adminClient();

    if (!(await verifyDeviceToken(supabaseAdmin, app_uuid, device_token))) {
      return jsonError("unauthorized", 401);
    }

    const token = Deno.env.get("FRP_TOKEN");
    if (!token) {
      console.error("FRP_TOKEN secret fehlt (supabase secrets set FRP_TOKEN=...)");
      return jsonError("tunnel_not_configured", 500);
    }

    return new Response(JSON.stringify({ token }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err: any) {
    return jsonError(err.message, 500);
  }
});
