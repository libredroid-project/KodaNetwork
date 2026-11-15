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
// verify-otp: checks the code against the stored hash, five tries per code, and
// marks it verified. the hash scheme matches send-otp (sha256 with the OTP pepper).
//
// POST { email, code } -> { valid: boolean, error?: string }
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { corsHeaders } from "../_shared/cors.ts";

const MAX_ATTEMPTS = 5;
const PEPPER = Deno.env.get("OTP_PEPPER") ?? "koda-otp";

function adminClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
  );
}

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function hash(email: string, code: string): Promise<string> {
  const data = new TextEncoder().encode(`${PEPPER}:${email.toLowerCase()}:${code}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ valid: false, error: "method_not_allowed" }, 405);

  try {
    const body = await req.json().catch(() => ({}));
    const email = String(body.email ?? "").trim().toLowerCase();
    const code = String(body.code ?? "").trim();
    if (!email || !/^[0-9]{6}$/.test(code)) return json({ valid: false, error: "invalid_code" }, 200);

    const admin = adminClient();
    const { data: row } = await admin
      .from("koda_otp_codes")
      .select("id, code_hash, attempts, expires_at, verified")
      .eq("email", email)
      .eq("verified", false)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (!row) return json({ valid: false, error: "no_code" }, 200);
    if (row.verified) return json({ valid: true }, 200);
    if (row.expires_at && new Date(row.expires_at).getTime() < Date.now()) {
      return json({ valid: false, error: "expired" }, 200);
    }
    if ((row.attempts ?? 0) >= MAX_ATTEMPTS) return json({ valid: false, error: "too_many_tries" }, 200);

    const matches = (await hash(email, code)) === row.code_hash;
    if (!matches) {
      await admin.from("koda_otp_codes")
        .update({ attempts: (row.attempts ?? 0) + 1 })
        .eq("id", row.id);
      return json({ valid: false, error: "wrong_code" }, 200);
    }

    await admin.from("koda_otp_codes").update({ verified: true }).eq("id", row.id);
    return json({ valid: true }, 200);
  } catch (e) {
    console.error("verify-otp error:", e);
    return json({ valid: false, error: "internal_error" }, 500);
  }
});
