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
// support-upload: hands out ONE short lived signed upload URL for a support
// chat photo. the anonymous insert policy on the bucket is gone, so this
// device checked function is the only way in. reads stay public on purpose:
// the ticket messages carry the plain URL and both the app and the admin
// panel render it directly.
//
// POST { app_uuid, device_token, content_type } -> { upload_url, public_url }
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient, jsonError, verifyDeviceToken } from "../_shared/deviceAuth.ts";

// photos are downscaled client side (max 1440px jpeg), so a small window is
// plenty. the bucket's own mime/size limits are the hard stop behind this.
const MAX_UPLOADS_PER_MINUTE = 10;
const ALLOWED: Record<string, string> = { "image/jpeg": "jpg", "image/png": "png" };

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  try {
    const body = await req.json();
    const supabaseAdmin = adminClient();

    if (!(await verifyDeviceToken(supabaseAdmin, body.app_uuid, body.device_token))) {
      return jsonError("unauthorized", 401);
    }

    const contentType = String(body.content_type ?? "image/jpeg").toLowerCase();
    const ext = ALLOWED[contentType];
    if (!ext) return jsonError("unsupported_type", 415);

    // abuse brake, a shared window in the database so it holds across instances
    try {
      const { data } = await supabaseAdmin.rpc("fn_api_rate_take", {
        p_key: "support_upload:" + String(body.app_uuid ?? ""),
        p_max: MAX_UPLOADS_PER_MINUTE,
        p_window_seconds: 60,
      });
      const retry = Number(data ?? 0) || 0;
      if (retry > 0) {
        return new Response(JSON.stringify({ error: "rate_limited", retry_after: retry }), {
          status: 429,
          headers: { ...corsHeaders, "Content-Type": "application/json", "Retry-After": String(retry) },
        });
      }
    } catch (_e) { /* a missing limiter must not take support down */ }

    const path = `${Date.now()}_${crypto.randomUUID().slice(0, 8)}.${ext}`;
    const { data: signed, error } = await supabaseAdmin.storage
      .from("support_attachments")
      .createSignedUploadUrl(path);
    if (error || !signed) return jsonError("sign_failed", 500);

    const base = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "");
    // signedUrl is absolute in current supabase-js, relative in older ones
    const raw = String(signed.signedUrl ?? "");
    const uploadUrl = raw.startsWith("http") ? raw : `${base}${raw.startsWith("/") ? "" : "/"}${raw}`;
    return new Response(JSON.stringify({
      path,
      token: signed.token,
      upload_url: uploadUrl,
      public_url: `${base}/storage/v1/object/public/support_attachments/${path}`,
    }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
  } catch (e: any) {
    return jsonError("internal_error: " + e.message, 500);
  }
});
