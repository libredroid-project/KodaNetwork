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
// call: the preview, the zip, the mails and the final purge.
//
// flow: a request arrives as PENDING. the admin opens it, asks for the preview, builds
// the zip (status READY) and only then approves it. approving an export sends the mail
// and flips it to FULFILLED, approving a deletion flips it to APPROVED and the purge
// runs as its own step.
//
// service role only. the admin panel (localhost), the admin app and the VPS call it:
//   POST { action: "preview", request_id }
//   POST { action: "build",   request_id }
//   POST { action: "decide",  request_id, decision: "APPROVED"|"REJECTED", admin_note }
//   POST { action: "purge",   request_id }
//   POST { action: "retention" }
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { zipSync, strToU8 } from "https://esm.sh/fflate@0.8.2";
import { sendKodaMail } from "../_shared/resend.ts";
import { isServiceRoleRequest } from "../_shared/serviceAuth.ts";

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

/** every query returns [] instead of failing, some tables only exist on the live project. */
async function safeList(query: PromiseLike<{ data: unknown; error: unknown }>): Promise<any[]> {
  try {
    const { data, error } = await query;
    return error ? [] : ((data as any[]) ?? []);
  } catch {
    return [];
  }
}

async function safeRow(query: PromiseLike<{ data: unknown; error: unknown }>): Promise<any | null> {
  try {
    const { data, error } = await query;
    return error ? null : ((data as any) ?? null);
  } catch {
    return null;
  }
}

/** everything KodaHosting stores for this device and account, grouped per area. */
async function collect(admin: Admin, request: any) {
  const appUuid = request.app_uuid;
  const authId = request.auth_id;
  const mail = await accountEmail(admin, authId);

  const users = await safeList(
    admin.from("koda_users").select("*").eq("app_uuid", appUuid),
  );
  const hwids = [...new Set(users.map((u: any) => u.hwid_full).filter(Boolean))];
  const codes = [...new Set(users.map((u: any) => u.code).filter(Boolean))];

  const servers = await safeList(
    // tombstoned rows (deleted_...) are leftovers, they do not belong in a review
    admin.from("koda_servers").select("*")
      .eq("owner_app_uuid", appUuid)
      .not("host", "like", "deleted_%"),
  );
  const hosts = servers.map((s: any) => s.host).filter(Boolean);

  const tickets = await safeList(
    admin.from("support_tickets").select("*").eq("reporter_uuid", appUuid),
  );
  const ticketIds = tickets.map((t: any) => t.id);

  const messages = ticketIds.length
    ? await safeList(
      admin.from("support_ticket_messages").select("*").in("ticket_id", ticketIds).limit(5000),
    )
    : [];

  const bugs = await safeList(
    admin.from("support_bugs").select("*").eq("reporter_uuid", appUuid),
  );
  const reports = await safeList(
    admin.from("support_reports").select("*").eq("reporter_uuid", appUuid),
  );

  const requests = await safeList(
    admin.from("data_requests").select("*").eq("app_uuid", appUuid),
  );

  const invites = await safeList(
    admin.from("server_invites").select("*").eq("created_by_app_uuid", appUuid),
  );

  const guests = authId
    ? await safeList(admin.from("server_guests").select("*").eq("auth_id", authId))
    : [];

  const transfersFromDevice = await safeList(
    admin.from("server_transfers").select("*").eq("from_app_uuid", appUuid),
  );
  const transfersByAuth = authId
    ? await safeList(admin.from("server_transfers").select("*").eq("auth_id", authId))
    : [];
  const transfers = [...transfersFromDevice];
  for (const transfer of transfersByAuth) {
    if (!transfers.some((t: any) => t.code === transfer.code)) transfers.push(transfer);
  }

  const profile = authId
    ? await safeRow(admin.from("account_profiles").select("*").eq("auth_id", authId).maybeSingle())
    : null;

  const extensions = authId
    ? await safeList(admin.from("extensions").select("*").eq("owner_auth_id", authId))
    : [];
  const extensionIds = extensions.map((e: any) => e.id);
  const extensionVersions = extensionIds.length
    ? await safeList(admin.from("extension_versions").select("*").in("extension_id", extensionIds))
    : [];

  const reads = await safeList(
    admin.from("app_notification_reads").select("*").eq("app_uuid", appUuid),
  );
  const mutes = await safeList(
    admin.from("app_notification_mutes").select("*").eq("app_uuid", appUuid),
  );
  const notifications = await safeList(
    admin.from("app_notifications").select("*").eq("target_app_uuid", appUuid).limit(200),
  );

  const hwidBans = hwids.length
    ? await safeList(admin.from("banned_hwids").select("*").in("hwid", hwids))
    : [];
  const highRisk = hwids.length
    ? await safeList(admin.from("high_risk_hwids").select("*").in("hwid", hwids))
    : [];

  const ports = hosts.length
    ? await safeList(admin.from("koda_ports").select("*").in("host", hosts))
    : [];

  const otpCodes = mail
    ? await safeList(admin.from("koda_otp_codes").select("*").eq("email", mail).limit(100))
    : [];
  const passwordResets = mail
    ? await safeList(admin.from("password_reset_requests").select("*").eq("email", mail).limit(100))
    : [];

  return {
    appUuid,
    authId,
    mail,
    users,
    servers,
    tickets,
    messages,
    bugs,
    reports,
    requests,
    invites,
    guests,
    transfers,
    profile,
    extensions,
    extensionVersions,
    reads,
    mutes,
    notifications,
    hwidBans,
    highRisk,
    ports,
    otpCodes,
    passwordResets,
    codes,
    hwids,
  };
}

/** all sections as plain JSON, so the admin app and the panel can render everything. */
function sectionsOf(data: any) {
  return {
    accounts: data.users,
    servers: data.servers,
    support_tickets: data.tickets,
    support_messages: data.messages,
    support_bugs: data.bugs,
    support_reports: data.reports,
    data_requests: data.requests,
    server_invites: data.invites,
    server_guests: data.guests,
    server_transfers: data.transfers,
    account_profile: data.profile,
    extensions: data.extensions,
    extension_versions: data.extensionVersions,
    notification_reads: data.reads,
    notification_settings: data.mutes,
    notifications: data.notifications,
    hwid_bans: data.hwidBans,
    high_risk_hwids: data.highRisk,
    ports: data.ports,
    otp_codes: data.otpCodes,
    password_reset_requests: data.passwordResets,
  };
}

/** the full review: every section, so the admin sees everything that will be sent. */
async function preview(admin: Admin, request: any, cors: Record<string, string>) {
  const data = await collect(admin, request);
  const sections = sectionsOf(data);
  const counts: Record<string, number> = {};
  for (const [key, value] of Object.entries(sections)) {
    counts[key] = Array.isArray(value) ? value.length : (value ? 1 : 0);
  }

  return json({
    ok: true,
    request: {
      id: request.id,
      kind: request.kind,
      status: request.status,
      requested_at: request.requested_at,
      note: request.note,
      export_path: request.export_path,
      export_expires_at: request.export_expires_at,
    },
    account: {
      app_uuid: data.appUuid,
      auth_id: data.authId,
      has_account: !!data.authId,
      email_set: !!data.mail,
      email_hint: data.mail
        ? data.mail.replace(/^(.)(.*)(@.*)$/, (_m, a, b, c) => a + "*".repeat(Math.min(6, b.length)) + c)
        : null,
      profile: data.profile,
    },
    counts,
    sections,
  }, 200, cors);
}

function escapeHtml(value: unknown): string {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function cell(value: unknown): string {
  if (value === null || value === undefined || value === "") return "–";
  if (typeof value === "object") return escapeHtml(JSON.stringify(value));
  return escapeHtml(value);
}

const SECTION_LABELS: Record<string, string> = {
  account_profile: "Profil",
  accounts: "Konten und Geräte",
  servers: "Server",
  support_tickets: "Support-Tickets",
  support_messages: "Ticket-Nachrichten",
  support_bugs: "Bug-Meldungen",
  support_reports: "Server-Meldungen",
  data_requests: "Datenanträge",
  server_invites: "Einladungen",
  server_guests: "Gastzugänge",
  server_transfers: "Gerätetransfers",
  extensions: "Extensions",
  extension_versions: "Extension-Versionen",
  notifications: "Benachrichtigungen",
  notification_reads: "Gelesene Benachrichtigungen",
  notification_settings: "Benachrichtigungs-Einstellungen",
  hwid_bans: "HWID-Sperren",
  high_risk_hwids: "Risiko-Einträge",
  ports: "Ports",
  otp_codes: "OTP-Vorgänge",
  password_reset_requests: "Passwort-Reset-Vorgänge",
};

function humanTable(title: string, rows: any[]): string {
  if (!rows.length) return `<h2>${escapeHtml(title)}</h2><p class="empty">Keine Einträge.</p>`;
  const keys = [...new Set(rows.flatMap((row) => Object.keys(row ?? {})))];
  const head = keys.map((key) => `<th>${escapeHtml(key)}</th>`).join("");
  const body = rows
    .map((row) => `<tr>${keys.map((key) => `<td>${cell(row?.[key])}</td>`).join("")}</tr>`)
    .join("");
  return `<h2>${escapeHtml(title)}</h2><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

/** the human readable version of the export, same content as the json files. */
function renderHumanHtml(doc: any): string {
  const sections = doc.sections ?? {};
  const parts: string[] = [];
  const seen = new Set<string>();

  if (sections.account_profile) {
    parts.push(humanTable(SECTION_LABELS.account_profile, [sections.account_profile]));
    seen.add("account_profile");
  }
  for (const [key, label] of Object.entries(SECTION_LABELS)) {
    if (seen.has(key)) continue;
    const value = sections[key];
    if (value === null || value === undefined) continue;
    if (Array.isArray(value)) {
      if (value.length === 0) continue;
      parts.push(humanTable(label, value));
    } else {
      parts.push(humanTable(label, [value]));
    }
  }

  return `<!doctype html>
<html lang="de"><head><meta charset="utf-8">
<title>KodaHosting Datenexport</title>
<style>
  body{margin:0;background:#141010;color:#D8D5D2;font-family:-apple-system,Segoe UI,Roboto,sans-serif;line-height:1.5}
  .wrap{max-width:1100px;margin:0 auto;padding:36px 24px}
  .brand{letter-spacing:3px;font-size:12px;margin-bottom:24px}
  .brand .a{color:#555}.brand .b{color:#AAA}.brand .c{color:#fff}
  h1{color:#F5F5F7;font-size:24px;margin:0 0 6px}
  .meta{color:#8A8A9A;font-size:13px;margin-bottom:26px}
  h2{color:#F5F5F7;font-size:16px;margin:30px 0 10px;border-bottom:1px solid #2A2226;padding-bottom:6px}
  table{width:100%;border-collapse:collapse;font-size:12px;margin-bottom:8px}
  th{text-align:left;color:#8A8A9A;font-weight:600;padding:6px 8px;border-bottom:1px solid #2A2226;white-space:nowrap}
  td{padding:6px 8px;border-bottom:1px solid #1F1917;vertical-align:top;word-break:break-word}
  tr:nth-child(even) td{background:#1B1614}
  .empty{color:#666;font-size:12px}
  footer{color:#555;font-size:11px;margin-top:30px}
</style></head>
<body><div class="wrap">
  <div class="brand"><span class="a">P.R.</span><span class="b">A.E.T.</span><span class="c">O.R.</span></div>
  <h1>Datenexport</h1>
  <div class="meta">
    Erstellt ${escapeHtml(doc.generated_at)} · Antrag ${escapeHtml(doc.request_id)}<br>
    Gerät ${escapeHtml(doc.app_uuid)} · Konto ${escapeHtml(doc.auth_id ?? "nicht verknüpft")}<br>
    E-Mail ${escapeHtml(doc.account?.email ?? "nicht registriert")}
  </div>
  ${parts.join("\n")}
  <footer>Diese Datei enthält dieselben Daten wie die JSON-Dateien im Archiv. KodaHosting, host.kodanetwork.eu</footer>
</div></body></html>`;
}

async function buildExport(admin: Admin, request: any, cors: Record<string, string>) {
  if (request.kind !== "EXPORT") return json({ error: "not_an_export" }, 400, cors);
  if (!["PENDING", "READY"].includes(request.status)) return json({ error: "wrong_status" }, 409, cors);

  const data = await collect(admin, request);
  const sections = sectionsOf(data);

  const doc = {
    format: "koda-export/2",
    generated_at: new Date().toISOString(),
    request_id: request.id,
    requested_at: request.requested_at,
    app_uuid: data.appUuid,
    auth_id: data.authId,
    account: {
      email: data.mail,
      profile: data.profile,
      app_uuids: [data.appUuid],
      account_codes: data.codes,
      hwid_hashes: data.hwids,
    },
    sections,
    note: "This archive contains every personal record KodaHosting stores about this device and account. export.html is the same content for humans, the .json files are for machines.",
  };

  const pretty = (value: unknown) => strToU8(JSON.stringify(value, null, 2));
  const files: Record<string, Uint8Array> = {
    "README.txt": strToU8(
      "KodaHosting Datenexport\n" +
      "=====================\n\n" +
      "export.html   - dieselben Daten zum Lesen im Browser\n" +
      "export.json   - alles in einer Datei (maschinenlesbar)\n\n" +
      "account.json        Konto, Geraete, Profil, Extensions\n" +
      "servers.json        alle Server\n" +
      "support.json        Tickets, Nachrichten, Bug- und Servermeldungen\n" +
      "requests.json       alle Datenantraege\n" +
      "notifications.json  Benachrichtigungen, Lesestaende, Einstellungen\n" +
      "network.json        Einladungen, Gastzugaenge, Transfers, Ports\n" +
      "moderation.json     HWID-Sperren und Risikoeintraege\n" +
      "security.json       OTP- und Passwort-Reset-Vorgaenge\n",
    ),
    "export.json": pretty(doc),
    "account.json": pretty({
      account: { email: data.mail, profile: data.profile },
      accounts: sections.accounts,
      extensions: sections.extensions,
      extension_versions: sections.extension_versions,
    }),
    "servers.json": pretty(sections.servers),
    "support.json": pretty({
      tickets: sections.support_tickets,
      messages: sections.support_messages,
      bugs: sections.support_bugs,
      reports: sections.support_reports,
    }),
    "requests.json": pretty(sections.data_requests),
    "notifications.json": pretty({
      notifications: sections.notifications,
      reads: sections.notification_reads,
      settings: sections.notification_settings,
    }),
    "network.json": pretty({
      invites: sections.server_invites,
      guests: sections.server_guests,
      transfers: sections.server_transfers,
      ports: sections.ports,
    }),
    "moderation.json": pretty({
      hwid_bans: sections.hwid_bans,
      high_risk_hwids: sections.high_risk_hwids,
    }),
    "security.json": pretty({
      otp_codes: sections.otp_codes,
      password_reset_requests: sections.password_reset_requests,
    }),
    "export.html": strToU8(renderHumanHtml(doc)),
  };

  const zipped = zipSync(files, { level: 6 });
  const path = `${data.appUuid}/${request.id}.zip`;

  await admin.storage.createBucket(BUCKET, { public: false }).catch(() => {});
  const { error: uploadError } = await admin.storage.from(BUCKET).upload(path, zipped, {
    contentType: "application/zip",
    upsert: true,
  });
  if (uploadError) return json({ error: "upload_failed", detail: uploadError.message }, 500, cors);

  const expiresAt = new Date(Date.now() + EXPORT_TTL_SECONDS * 1000).toISOString();
  await admin.from("data_requests")
    .update({ status: "READY", export_path: path, export_expires_at: expiresAt })
    .eq("id", request.id);

  return json({
    ok: true,
    export_path: path,
    size: zipped.length,
    expires_at: expiresAt,
    sections: Object.fromEntries(Object.entries(sections).map(([k, v]) => [k, Array.isArray(v) ? v.length : (v ? 1 : 0)])),
  }, 200, cors);
}

async function decide(admin: Admin, body: any, cors: Record<string, string>) {
  const decision = String(body.decision ?? "").toUpperCase();
  if (decision !== "APPROVED" && decision !== "REJECTED") {
    return json({ error: "unknown_decision" }, 400, cors);
  }
  const request = await requestRow(admin, String(body.request_id ?? ""));
  if (!request) return json({ error: "not_found" }, 404, cors);

  if (decision === "APPROVED" && request.kind === "EXPORT" && !request.export_path) {
    return json({ error: "build_first" }, 409, cors);
  }
  if (decision === "APPROVED" && request.kind === "EXPORT" && request.status === "FULFILLED") {
    return json({ error: "already_sent" }, 409, cors);
  }

  const { error } = await admin.rpc("rpc_admin_decide_data_request", {
    p_request_id: request.id,
    p_status: decision,
    p_admin_note: body.admin_note ?? null,
  });
  if (error) return json({ error: "decide_failed", detail: error.message }, 500, cors);

  const mail = await accountEmail(admin, request.auth_id);
  let mailed = false;

  if (decision === "APPROVED" && request.kind === "EXPORT") {
    // the zip was built and reviewed before this, now it goes out
    const { data: signed } = await admin.storage.from(BUCKET)
      .createSignedUrl(String(request.export_path), EXPORT_TTL_SECONDS);
    const link = signed?.signedUrl ?? "";

    await admin.from("data_requests")
      .update({ status: "FULFILLED", fulfilled_at: new Date().toISOString() })
      .eq("id", request.id);

    await notify(
      admin,
      request.id,
      request.app_uuid,
      "data_export_ready",
      "Datenexport bereit",
      "Dein Datenexport steht bereit, du kannst ihn in der App oder per E-Mail herunterladen.",
    );

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
    return json({ ok: true, status: "FULFILLED", mailed }, 200, cors);
  }

  if (mail) {
    const approved = decision === "APPROVED";
    const sent = await sendKodaMail({
      to: mail,
      subject: approved ? "Dein KodaHosting Antrag wurde genehmigt" : "Dein KodaHosting Antrag wurde abgelehnt",
      heading: approved ? "Antrag genehmigt" : "Antrag abgelehnt",
      paragraphs: approved
        ? ["Deine Löschanfrage wurde genehmigt. Wir löschen dein Konto und alle Daten, die wir nicht gesetzlich aufbewahren müssen."]
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

async function purgeData(admin: Admin, request: any, cors: Record<string, string>) {
  if (request.kind !== "DELETE") return json({ error: "not_a_delete_request" }, 400, cors);
  if (request.status !== "APPROVED") return json({ error: "not_approved" }, 409, cors);

  const mail = await accountEmail(admin, request.auth_id);

  const { data: summary, error: purgeError } = await admin.rpc("fn_snapshot_and_purge_user", {
    p_app_uuid: request.app_uuid,
    p_original_user_id: null,
  });
  if (purgeError) return json({ error: "purge_failed", detail: purgeError.message }, 500, cors);

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

Deno.serve(async (req) => {
  const cors = corsFor(req);
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405, cors);

  // only the admin tools (service role, from the panel, the admin app or the VPS)
  if (!isServiceRoleRequest(req)) return json({ error: "unauthorized" }, 401, cors);

  try {
    const admin = adminClient();
    const body = await req.json().catch(() => ({}));
    const action = String(body.action ?? "");

    if (action === "retention") {
      const { data, error } = await admin.rpc("fn_purge_expired_retained");
      if (error) return json({ error: "retention_failed", detail: error.message }, 500, cors);
      return json({ ok: true, removed: data ?? 0 }, 200, cors);
    }

    // diagnostics: which domains does the configured Resend key actually see?
    if (action === "mailstatus") {
      const key = Deno.env.get("RESEND_API_KEY");
      if (!key) return json({ ok: false, error: "RESEND_API_KEY missing" }, 200, cors);
      const response = await fetch("https://api.resend.com/domains", {
        headers: { Authorization: `Bearer ${key}` },
      });
      const payload = await response.json().catch(() => ({}));
      const domains = Array.isArray(payload?.data)
        ? payload.data.map((d: any) => ({ name: d.name, status: d.status }))
        : [];
      return json({
        ok: response.ok,
        status: response.status,
        from: Deno.env.get("KODA_MAIL_FROM") || "KodaHosting <no-reply@kodaserv.eu>",
        domains,
        error: response.ok ? null : (payload?.message ?? null),
      }, 200, cors);
    }

    const request = await requestRow(admin, String(body.request_id ?? ""));
    if (!request) return json({ error: "not_found" }, 404, cors);

    if (action === "preview") return await preview(admin, request, cors);
    if (action === "decide") return await decide(admin, body, cors);
    if (action === "build") return await buildExport(admin, request, cors);
    if (action === "purge") return await purgeData(admin, request, cors);

    return json({ error: "unknown_action" }, 400, cors);
  } catch (e) {
    console.error("data-request error:", e);
    return json({ error: "internal_error" }, 500, cors);
  }
});
