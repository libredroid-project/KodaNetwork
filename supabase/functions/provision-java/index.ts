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
// hands out a signed download URL for a bundled JDK. two holes fixed: the caller
// was never identified (anyone with the anon key could mint signed URLs) and the
// arch value was string-interpolated into the object path, so "a/../../x" signed
// arbitrary objects in the bucket. arch is allowlisted and the device verified now.
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient, jsonError, verifyDeviceToken } from "../_shared/deviceAuth.ts";

const ALLOWED_ARCH = new Set(["aarch64", "armv7", "x86_64"]);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405, headers: corsHeaders });
  }

  try {
    const body = await req.json();
    const supabaseAdmin = adminClient();

    const { app_uuid, device_token } = body;
    if (!(await verifyDeviceToken(supabaseAdmin, app_uuid, device_token))) {
      return jsonError("unauthorized", 401);
    }

    const arch = String(body.arch ?? "aarch64");
    if (!ALLOWED_ARCH.has(arch)) {
      return jsonError("unsupported_arch", 400);
    }

    const bucket = Deno.env.get("JDK_STORAGE_BUCKET") ?? "artifacts";
    const objectPath = `openjdk17/openjdk17-${arch}.tar.gz`;
    const checksum = Deno.env.get(`OPENJDK17_SHA256_${arch.toUpperCase()}`) ?? "";

    const { data, error } = await supabaseAdmin.storage.from(bucket).createSignedUrl(objectPath, 60 * 10);
    if (error || !data?.signedUrl) {
      return jsonError("signed_url_failed", 500);
    }

    return new Response(
      JSON.stringify({
        bucket,
        objectPath,
        checksum,
        signedUrl: data.signedUrl,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } },
    );
  } catch (e: any) {
    return jsonError(e.message, 500);
  }
});
