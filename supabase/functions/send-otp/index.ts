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
// send-otp: generates the six digit code, stores only its hash and mails it.
// the mail goes out with the shared Resend sender (no-reply@kodaserv.eu); the old
// live function failed with "mail_send_fail" because its sender was unverified.
//
// POST { email } -> { ok: true }
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { corsHeaders } from "../_shared/cors.ts";
import { sendKodaMail } from "../_shared/resend.ts";

const CODE_TTL_MINUTES = 10;
const MAX_PER_WINDOW = 5;
const WINDOW_MINUTES = 10;
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
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  try {
    const body = await req.json().catch(() => ({}));
    const email = String(body.email ?? "").trim().toLowerCase();
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || email.length > 254) {
      return json({ error: "invalid_email" }, 400);
    }

    const admin = adminClient();
    const since = new Date(Date.now() - WINDOW_MINUTES * 60 * 1000).toISOString();
    const { count } = await admin
      .from("koda_otp_codes")
      .select("id", { count: "exact", head: true })
      .eq("email", email)
      .gte("created_at", since);
    if ((count ?? 0) >= MAX_PER_WINDOW) return json({ error: "slow_down" }, 429);

    // crypto random, Math.random must never generate a login code
    const random = new Uint32Array(1);
    crypto.getRandomValues(random);
    const code = String(100000 + (random[0] % 900000));
    const { error: insertError } = await admin.from("koda_otp_codes").insert({
      email,
      code_hash: await hash(email, code),
      purpose: "signup",
      attempts: 0,
      verified: false,
      expires_at: new Date(Date.now() + CODE_TTL_MINUTES * 60 * 1000).toISOString(),
    });
    if (insertError) return json({ error: "internal_error" }, 500);

    const sent = await sendKodaMail({
      to: email,
      subject: "Your KodaHosting verification code",
      heading: "Your code",
      paragraphs: [
        `Your verification code is ${code}. It is valid for ${CODE_TTL_MINUTES} minutes.`,
        "If you did not request this code, you can ignore this mail.",
      ],
    });
    if (!sent.ok) {
      // do not keep a code that never reached the inbox
      await admin.from("koda_otp_codes").delete().eq("email", email).eq("code_hash", await hash(email, code));
      return json({ error: "mail_send_fail", detail: sent.error ?? null }, 502);
    }

    return json({ ok: true }, 200);
  } catch (e) {
    console.error("send-otp error:", e);
    return json({ error: "internal_error" }, 500);
  }
});
