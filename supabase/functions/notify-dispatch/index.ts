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
// notify-dispatch: the e-mail mirror for one notification. Only devices that enabled
// "also by e-mail" for this category get the mail, so nobody is spammed.
//
// service role only. called by the admin panel / admin app after the insert.
//   POST { notification_id } -> { sent, skipped }
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { sendKodaMail } from "../_shared/resend.ts";
import { isServiceRoleRequest } from "../_shared/serviceAuth.ts";

const MAX_RECIPIENTS = 500;

function adminClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
  );
}

const ALLOWED_ORIGINS = new Set([
  "https://kodanetwork.eu",
  "https://host.kodanetwork.eu",
  "http://localhost:5173",
  "http://127.0.0.1:5173",
]);

function corsFor(req: Request) {
  const origin = req.headers.get("origin") ?? "";
  return {
    "Access-Control-Allow-Origin": ALLOWED_ORIGINS.has(origin) ? origin : "https://kodanetwork.eu",
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
  };
}

function json(body: unknown, status: number, cors: Record<string, string>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  const cors = corsFor(req);
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: cors });
  }
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405, cors);

  if (!isServiceRoleRequest(req)) return json({ error: "unauthorized" }, 401, cors);

  try {
    const admin = adminClient();
    const body = await req.json().catch(() => ({}));
    const id = String(body.notification_id ?? "");
    if (!id) return json({ error: "missing_notification_id" }, 400);

    const { data: notification } = await admin
      .from("app_notifications")
      .select("*")
      .eq("id", id)
      .maybeSingle();
    if (!notification) return json({ error: "not_found" }, 404);
    if (!notification.email_mirror) return json({ sent: 0, skipped: "no_mirror" }, 200);

    // who wants mail for this category?
    let appUuids: string[] = [];
    if (notification.target_app_uuid) {
      const { data: mute } = await admin
        .from("app_notification_mutes")
        .select("app_uuid")
        .eq("app_uuid", notification.target_app_uuid)
        .eq("kind", notification.kind)
        .eq("email_enabled", true)
        .maybeSingle();
      appUuids = mute ? [notification.target_app_uuid] : [];
    } else {
      const { data: mutes } = await admin
        .from("app_notification_mutes")
        .select("app_uuid")
        .eq("kind", notification.kind)
        .eq("email_enabled", true)
        .limit(MAX_RECIPIENTS);
      appUuids = (mutes ?? []).map((m: any) => m.app_uuid);
    }
    if (!appUuids.length) return json({ sent: 0, skipped: "no_opt_in" }, 200);

    const { data: users } = await admin
      .from("koda_users")
      .select("app_uuid, auth_id")
      .in("app_uuid", appUuids)
      .not("auth_id", "is", null);

    const authIds = [...new Set((users ?? []).map((u: any) => u.auth_id))].slice(0, MAX_RECIPIENTS);
    const mails = new Set<string>();
    for (const authId of authIds) {
      const { data } = await admin.auth.admin.getUserById(authId);
      const mail = data?.user?.email;
      if (mail) mails.add(mail);
    }

    let sent = 0;
    const failures: string[] = [];
    for (const mail of mails) {
      const result = await sendKodaMail({
        to: mail,
        subject: notification.title,
        heading: notification.title,
        paragraphs: [notification.body],
        footnote: "Du bekommst diese Mail, weil du für diese Kategorie E-Mail-Kopien aktiviert hast. In der App kannst du das jederzeit ändern.",
      });
      if (result.ok) sent++;
      else failures.push(`resend ${result.status}: ${result.error ?? "unknown"}`);
    }

    return json({ sent, failed: failures.length, errors: failures.slice(0, 3), skipped: null }, 200);
  } catch (e) {
    console.error("notify-dispatch error:", e);
    return json({ error: "internal_error" }, 500);
  }
});
