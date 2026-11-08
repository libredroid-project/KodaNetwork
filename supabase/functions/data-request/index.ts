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
// data-request: everything around a DSGVO request that needs more than a database
// call: building the export file, sending the mails and running the final purge.
//
// service role only. the admin panel (localhost), the admin app and the VPS call it:
//   POST { action: "decide", request_id, decision: "APPROVED"|"REJECTED", admin_note }
//   POST { action: "build",  request_id }
//   POST { action: "purge",  request_id }
//   POST { action: "retention" }            -> removes expired retention snapshots
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { sendKodaMail } from "../_shared/resend.ts";

const BUCKET = "data_exports";
const EXPORT_TTL_SECONDS = 60 * 60 * 24 * 7; // seven days, then the link dies

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

function adminClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
  );
}

type Admin = ReturnType<typeof adminClient>;

async function requestRow(admin: Admin, id: string) {
  const { data } = await admin.from("data_requests").select("*").eq("id", id).maybeSingle();
  return data;
}

async function accountEmail(admin: Admin, authId: string | null): Promise<string | null> {
  if (!authId) return null;
  const { data } = await admin.auth.admin.getUserById(authId);
  return data?.user?.email ?? null;
}

async function notify(
  admin: Admin,
  requestId: string,
  appUuid: string,
  key: string,
  title: string,
  body: string,
) {
  await admin.from("app_notifications").insert({
    kind: "DATA",
    title,
    body,
    action: `data_request:${requestId}`,
    target_app_uuid: appUuid,
    severity: "IMPORTANT",
    created_by: "system",
    i18n_key: key,
    i18n_args: { request_id: requestId },
  });
}

async function buildExport(admin: Admin, request: any, cors: Record<string, string>) {
  if (request.kind !== "EXPORT") return json({ error: "not_an_export" }, 400, cors);
  if (request.status !== "APPROVED") return json({ error: "not_approved" }, 409, cors);

  const appUuid = request.app_uuid;
  const authId = request.auth_id;

  const byApp = (table: string, column = "app_uuid") =>
    admin.from(table).select("*").eq(column, appUuid);

  const [{ data: users }, { data: servers }, { data: tickets }, { data: requests }, { data: invites }, { data: reads }, { data: mutes }] =
    await Promise.all([
      byApp("koda_users"),
      admin.from("koda_servers").select("*").eq("owner_app_uuid", appUuid),
      admin.from("support_tickets").select("*").eq("reporter_uuid", appUuid),
      byApp("data_requests"),
      admin.from("server_invites").select("*").eq("created_by_app_uuid", appUuid),
      byApp("app_notification_reads"),
      byApp("app_notification_mutes"),
    ]);

  const ticketIds = (tickets ?? []).map((t: any) => t.id);
  const { data: messages } = ticketIds.length
    ? await admin.from("support_ticket_messages").select("*").in("ticket_id", ticketIds).limit(5000)
    : { data: [] };

  const { data: profile } = authId
    ? await admin.from("account_profiles").select("*").eq("auth_id", authId).maybeSingle()
    : { data: null };

  const { data: extensions } = authId
    ? await admin.from("extensions").select("*").eq("owner_auth_id", authId)
    : { data: [] };

  const exportDoc = {
    format: "koda-export/1",
    generated_at: new Date().toISOString(),
    request_id: request.id,
    requested_at: request.requested_at,
    app_uuid: appUuid,
    auth_id: authId,
    profile,
    devices_and_accounts: users ?? [],
    servers: servers ?? [],
    support_tickets: tickets ?? [],
    support_messages: messages ?? [],
    data_requests: requests ?? [],
    server_invites: invites ?? [],
    extensions: extensions ?? [],
    notification_reads: reads ?? [],
    notification_settings: mutes ?? [],
    note: "This file contains every personal record KodaHosting stores about this device and account.",
  };

  const path = `${appUuid}/${request.id}.json`;
  const bytes = new TextEncoder().encode(JSON.stringify(exportDoc, null, 2));

  await admin.storage.createBucket(BUCKET, { public: false }).catch(() => {});
  const { error: uploadError } = await admin.storage.from(BUCKET).upload(path, bytes, {
    contentType: "application/json",
    upsert: true,
  });
  if (uploadError) return json({ error: "upload_failed", detail: uploadError.message }, 500, cors);

  const expiresAt = new Date(Date.now() + EXPORT_TTL_SECONDS * 1000).toISOString();
  await admin.from("data_requests")
    .update({
      status: "FULFILLED",
      fulfilled_at: new Date().toISOString(),
      export_path: path,
      export_expires_at: expiresAt,
    })
    .eq("id", request.id);

  const { data: signed } = await admin.storage.from(BUCKET).createSignedUrl(path, EXPORT_TTL_SECONDS);
  const link = signed?.signedUrl ?? "";

  await notify(
    admin,
    request.id,
    appUuid,
    "data_export_ready",
    "Datenexport bereit",
    "Dein Datenexport steht bereit, du kannst ihn in der App oder per E-Mail herunterladen.",
  );

  const mail = await accountEmail(admin, authId);
  let mailed = false;
  if (mail) {
    const sent = await sendKodaMail({
      to: mail,
      subject: "Dein KodaHosting Datenexport",
      heading: "Deine Daten sind bereit",
      paragraphs: [
        "Wir haben alle personenbezogenen Daten zusammengestellt, die KodaHosting zu diesem Gerät und diesem Konto gespeichert hat.",
        "Der Link ist sieben Tage gültig. Danach kannst du jederzeit einen neuen Export beantragen.",
      ],
      actionLabel: "Datenexport herunterladen",
      actionUrl: link || "https://host.kodanetwork.eu/dashboard",
      footnote: "Wenn du den Export nicht angefordert hast, ignoriere diese Mail und melde dich beim Support.",
    });
    mailed = sent.ok;
    if (mailed) {
      await admin.from("data_requests").update({ email_sent_at: new Date().toISOString() }).eq("id", request.id);
    }
  }

  return json({ ok: true, export_path: path, expires_at: expiresAt, mailed }, 200, cors);
}

async function purgeData(admin: Admin, request: any, cors: Record<string, string>) {
  if (request.kind !== "DELETE") return json({ error: "not_a_delete_request" }, 400, cors);
  if (request.status !== "APPROVED") return json({ error: "not_approved" }, 409, cors);

  const mail = await accountEmail(admin, request.auth_id);

  const { data: summary, error: purgeError } = await admin.rpc("fn_snapshot_and_purge_user", {
    p_app_uuid: request.app_uuid,
    p_original_user_id: null,
  });
  if (purgeError) return json({ error: "purge_failed", detail: purgeError.message }, 500, cors);

  // the login is part of "everything": delete the auth user after the snapshot
  if (request.auth_id) {
    await admin.auth.admin.deleteUser(request.auth_id).catch(() => {});
  }

  await admin.from("data_requests")
    .update({ status: "FULFILLED", fulfilled_at: new Date().toISOString() })
    .eq("id", request.id);

  let mailed = false;
  if (mail) {
    const sent = await sendKodaMail({
      to: mail,
      subject: "Dein KodaHosting Konto wurde gelöscht",
      heading: "Deine Daten sind gelöscht",
      paragraphs: [
        "Dein Konto und alle personenbezogenen Daten wurden gelöscht.",
        "Nur die Daten, die wir gesetzlich aufbewahren müssen (Abrechnung und Vertragsnachweise), liegen noch in einer getrennten Aufbewahrungstabelle. Sie werden automatisch gelöscht, sobald die gesetzliche Frist endet.",
      ],
      footnote: "Wenn du Fragen dazu hast, antworte einfach auf diese Mail oder nutze contact@kodaserv.eu.",
    });
    mailed = sent.ok;
    if (mailed) {
      await admin.from("data_requests").update({ email_sent_at: new Date().toISOString() }).eq("id", request.id);
    }
  }

  return json({ ok: true, summary, mailed }, 200, cors);
}

async function decide(
  admin: Admin,
  body: any,
  cors: Record<string, string>,
) {
  const decision = String(body.decision ?? "").toUpperCase();
  if (decision !== "APPROVED" && decision !== "REJECTED") {
    return json({ error: "unknown_decision" }, 400, cors);
  }
  const request = await requestRow(admin, String(body.request_id ?? ""));
  if (!request) return json({ error: "not_found" }, 404, cors);

  const { error } = await admin.rpc("rpc_admin_decide_data_request", {
    p_request_id: request.id,
    p_status: decision,
    p_admin_note: body.admin_note ?? null,
  });
  if (error) return json({ error: "decide_failed", detail: error.message }, 500, cors);

  const mail = await accountEmail(admin, request.auth_id);
  let mailed = false;
  if (mail) {
    const approved = decision === "APPROVED";
    const sent = await sendKodaMail({
      to: mail,
      subject: approved ? "Dein KodaHosting Antrag wurde genehmigt" : "Dein KodaHosting Antrag wurde abgelehnt",
      heading: approved ? "Antrag genehmigt" : "Antrag abgelehnt",
      paragraphs: approved
        ? [
          request.kind === "EXPORT"
            ? "Wir stellen deine Daten zusammen. Sobald der Export fertig ist, bekommst du eine weitere Mail und eine Benachrichtigung in der App."
            : "Deine Löschanfrage wurde genehmigt. Wir löschen dein Konto und alle Daten, die wir nicht gesetzlich aufbewahren müssen.",
        ]
        : [
          "Dein Antrag wurde abgelehnt.",
          body.admin_note ? `Begründung: ${String(body.admin_note)}` : "Bei Fragen antworte einfach auf diese Mail.",
        ],
      footnote: "Du kannst den Status jederzeit in der App unter Konto → Meine Anträge sehen.",
    });
    mailed = sent.ok;
    if (mailed) {
      await admin.from("data_requests").update({ email_sent_at: new Date().toISOString() }).eq("id", request.id);
    }
  }

  return json({ ok: true, status: decision, mailed }, 200, cors);
}

Deno.serve(async (req) => {
  const cors = corsFor(req);
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405, cors);

  // only the admin tools hold the service role key
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const auth = req.headers.get("authorization") ?? "";
  const bearer = auth.toLowerCase().startsWith("bearer ") ? auth.slice(7) : "";
  if (!serviceKey || bearer !== serviceKey) return json({ error: "unauthorized" }, 401, cors);

  try {
    const admin = adminClient();
    const body = await req.json().catch(() => ({}));
    const action = String(body.action ?? "");

    if (action === "retention") {
      const { data, error } = await admin.rpc("fn_purge_expired_retained");
      if (error) return json({ error: "retention_failed", detail: error.message }, 500, cors);
      return json({ ok: true, removed: data ?? 0 }, 200, cors);
    }

    const request = await requestRow(admin, String(body.request_id ?? ""));
    if (!request) return json({ error: "not_found" }, 404, cors);

    if (action === "decide") return await decide(admin, body, cors);
    if (action === "build") return await buildExport(admin, request, cors);
    if (action === "purge") return await purgeData(admin, request, cors);

    return json({ error: "unknown_action" }, 400, cors);
  } catch (e) {
    console.error("data-request error:", e);
    return json({ error: "internal_error" }, 500, cors);
  }
});
