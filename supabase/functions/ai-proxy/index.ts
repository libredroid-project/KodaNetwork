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
// talks to the OpenRouter models for the crash analysis. the key used to sit
// obfuscated inside the APK (a 30 second unzip and xor for anyone), so every
// install carried a billable credential. the key lives in the function secrets
// now and only a verified device gets an answer.
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient, jsonError, verifyDeviceToken } from "../_shared/deviceAuth.ts";

// a hard ceiling for the spend, whatever a client asks for
const MAX_TOKENS_CEILING = 4000;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  try {
    const appUuid = req.headers.get("x-koda-app-uuid") ?? "";
    const deviceToken = req.headers.get("x-koda-device-token") ?? "";

    const supabaseAdmin = adminClient();
    if (!(await verifyDeviceToken(supabaseAdmin, appUuid, deviceToken))) {
      return jsonError("unauthorized", 401);
    }

    const key = Deno.env.get("OPENROUTER_KEY") ?? "";
    if (!key) {
      return jsonError("ai_not_configured", 500);
    }

    const body = await req.json();
    const maxTokens = Number(body.max_tokens ?? 0);
    if (!Number.isFinite(maxTokens) || maxTokens <= 0 || maxTokens > MAX_TOKENS_CEILING) {
      body.max_tokens = MAX_TOKENS_CEILING;
    }

    const upstream = await fetch("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${key}`,
      },
      body: JSON.stringify(body),
    });

    const text = await upstream.text();
    return new Response(text, {
      status: upstream.status,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    return jsonError(e.message, 500);
  }
});
