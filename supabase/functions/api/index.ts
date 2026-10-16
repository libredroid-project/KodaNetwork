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
// KodaHosting public API, served as api.kodanetwork.eu (nginx on the tunnel server
// proxies /v1/* onto this function). One router, two auth levels:
//
//   public (no auth)                JWT (supabase access token)      service role
//   GET /                           GET  /v1/me                      POST /v1/admin/dns-bootstrap
//   GET /v1/status                  PATCH /v1/me
//   GET /v1/servers                 POST /v1/me/avatar
//   GET /v1/servers/{host}          DELETE /v1/me/avatar
//   GET /v1/invite/{token}          GET  /v1/me/extensions
//   GET /v1/releases
//   GET /v1/extensions
//   GET /v1/extensions/{id}         GET  /v1/me/extensions
//   GET /v1/extensions/{id}/download GET  /v1/me/tickets
//   POST /v1/extensions             GET  /v1/me/tickets/{id}
//   POST /v1/extensions/{id}/versions  POST /v1/me/tickets/{id}/messages
//   POST /v1/extensions/{id}/finalize
//   POST /v1/extensions/{id}/withdraw
//
// extension packages are private; downloads hand out 60 second signed urls and bump
// the counter. personal (mental support) tickets never appear here: they are end to
// end encrypted between the app and the admin app, the web can show normal tickets only.
//
// deployed with verify_jwt = false (see supabase/config.toml), because the public
// routes must work without any token and the JWT routes verify themselves.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { unzipSync } from "https://esm.sh/fflate@0.8.2";

const API_NAME = "KodaHosting API";
const API_VERSION = "1";

const ALLOWED_ORIGINS = new Set([
  "https://kodanetwork.eu",
  "https://www.kodanetwork.eu",
  "https://host.kodanetwork.eu",
]);

// same list the app runtime knows (ExtensionManifest.BASE_PERMISSIONS)
const BASE_PERMISSIONS = new Set([
  "storage", "servers.read", "console.read", "console.send", "servers.control",
  "players.read", "players.actions", "files.read", "notifications",
]);

const CATEGORIES = ["tools", "ui", "language", "theme", "gameplay", "other"];

const ID_PATTERN = /^[a-z0-9]+(\.[a-z0-9_-]+)+$/;
const VERSION_PATTERN = /^[0-9]+(\.[0-9]+)*$/;
const MAX_PACKAGE_BYTES = 20971520; // 20 MB, mirrors the manifest cap in the app
const MAX_PENDING_SUBMISSIONS = 3;
const MAX_PENDING_PER_EXTENSION = 1; // enforced by a partial unique index as well

function adminClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
    { auth: { persistSession: false } },
  );
}

type Admin = ReturnType<typeof createClient>;

function corsFor(origin: string | null): Record<string, string> {
  const headers: Record<string, string> = {
    "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "authorization, content-type, apikey, x-client-info",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
  // production origins plus local development (a locally served copy of the
  // website must be able to talk to the api while testing)
  const isAllowed = !!origin && (ALLOWED_ORIGINS.has(origin)
    || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin));
  if (isAllowed) headers["Access-Control-Allow-Origin"] = origin;
  return headers;
}

function json(body: unknown, status: number, cors: Record<string, string>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function fail(error: string, status: number, cors: Record<string, string>) {
  return json({ ok: false, error }, status, cors);
}

function str(v: unknown, max: number): string | null {
  if (typeof v !== "string") return null;
  const t = v.trim();
  if (!t || t.length > max) return null;
  return t;
}

function optStr(v: unknown, max: number): string | null {
  if (v == null || v === "") return null;
  if (typeof v !== "string") return null;
  const t = v.trim();
  if (!t || t.length > max) return null;
  return t;
}

function httpUrl(v: unknown, max = 300): string | null {
  const t = optStr(v, max);
  if (!t) return null;
  return /^https:\/\/[^\s]+$/i.test(t) ? t : null;
}

function intIn(v: unknown, min: number, max: number): number | null {
  const n = typeof v === "string" ? Number(v) : typeof v === "number" ? v : NaN;
  if (!Number.isFinite(n) || !Number.isInteger(n)) return null;
  if (n < min || n > max) return null;
  return n;
}

function cleanPermissions(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  for (const p of v) {
    if (typeof p === "string" && BASE_PERMISSIONS.has(p) && !out.includes(p)) out.push(p);
  }
  return out.slice(0, 16);
}

async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function requireUser(req: Request, admin: Admin): Promise<{ id: string; email: string | null; created_at: string | null } | null> {
  const header = req.headers.get("authorization") ?? "";
  const token = header.toLowerCase().startsWith("bearer ") ? header.slice(7).trim() : "";
  if (!token) return null;
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data?.user) return null;
  return { id: data.user.id, email: data.user.email ?? null, created_at: data.user.created_at ?? null };
}

/** all device rows that belong to this account (the app links them via auth_id). */
async function appUuidsFor(admin: Admin, authId: string): Promise<string[]> {
  const { data } = await admin.from("koda_users").select("app_uuid").eq("auth_id", authId);
  return (data ?? []).map((r: any) => String(r.app_uuid)).filter(Boolean);
}

// ---------------------------------------------------------------------------
// rate limiting: sliding windows in the database (public.api_rate_limits +
// fn_api_rate_take), so the limits hold across every warm instance instead of
// per instance. this is the abuse brake in front of everything; the sharper
// per-feature limits (submissions, ticket messages, uploads) stack on top.
// ---------------------------------------------------------------------------

const RATE_PER_IP_READ = 120; // per minute
const RATE_PER_IP_WRITE = 30;
const RATE_PER_USER = 90;
const RATE_TICKET_MESSAGES = 20;
const RATE_ASSET_UPLOADS = 20;

/** 0 when allowed, otherwise the seconds until the caller may retry. */
async function rateTake(admin: Admin, key: string, max: number): Promise<number> {
  try {
    const { data, error } = await admin.rpc("fn_api_rate_take", {
      p_key: key,
      p_max: max,
      p_window_seconds: 60,
    });
    if (error) return 0; // a missing limiter must not take the api down
    return Number(data ?? 0) || 0;
  } catch (_e) {
    return 0;
  }
}

function clientIp(req: Request): string {
  // our nginx sets this to the true client (it overwrites whatever arrives),
  // cloudflare's cf-connecting-ip is the fallback for direct calls to the
  // function url, x-forwarded-for the last resort.
  const fromNginx = req.headers.get("x-koda-client-ip");
  if (fromNginx) return fromNginx.trim();
  const cf = req.headers.get("cf-connecting-ip");
  if (cf) return cf.trim();
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const first = forwarded.split(",")[0].trim();
  return first || "unknown";
}

function tooMany(retryAfterSeconds: number, cors: Record<string, string>) {
  return new Response(
    JSON.stringify({ ok: false, error: "rate_limited", retry_after: retryAfterSeconds }),
    {
      status: 429,
      headers: {
        ...cors,
        "Content-Type": "application/json",
        "Retry-After": String(retryAfterSeconds),
        "Cache-Control": "no-store",
      },
    },
  );
}

// rate limit for web ticket replies: RATE_TICKET_MESSAGES per minute per
// account, the same brake the app-side rpc_create_ticket_message applies per
// device (enforced through fn_api_rate_take so it holds across instances).

function jsonBody(req: Request): Promise<any> {
  return req.json().catch(() => ({}));
}

// ---------------------------------------------------------------------------
// status
// ---------------------------------------------------------------------------

async function buildStatus(admin: Admin) {
  const [settingsRes, serversRes, extCountRes, vpsRes, releaseRes] = await Promise.all([
    admin.from("app_settings").select("key,value").in("key", ["maintenance_mode"]),
    admin.from("koda_servers").select("server_version,online_players").not("host", "like", "deleted_%"),
    admin.from("extensions").select("id", { count: "exact", head: true }).eq("status", "APPROVED"),
    admin.from("vps_stats").select("*").order("created_at", { ascending: false }).limit(1).maybeSingle(),
    admin.from("releases").select("version_code,version_name,channel,created_at")
      .eq("is_published", true).order("version_code", { ascending: false }).limit(1).maybeSingle(),
  ]);

  const settings: Record<string, any> = {};
  for (const row of settingsRes.data ?? []) settings[(row as any).key] = (row as any).value;
  const maintenance = settings["maintenance_mode"] ?? null;

  let serversTotal = 0;
  let playersOnline = 0;
  for (const s of serversRes.data ?? []) {
    const state = String((s as any).server_version ?? "");
    if (state === "" || state === "HIBERNATED") continue;
    serversTotal += 1;
    playersOnline += Number((s as any).online_players ?? 0) || 0;
  }

  const vps = vpsRes.data as any;
  const vpsAgeMs = vps?.created_at ? Date.now() - Date.parse(vps.created_at) : null;
  const vpsBlocks = vps
    ? {
      measured_at: vps.created_at,
      stale: vpsAgeMs == null ? true : vpsAgeMs > 5 * 60_000,
      cpu_cores: vps.cpu_cores,
      load1: vps.load1,
      ram_total_mb: vps.ram_total_mb,
      ram_used_mb: vps.ram_used_mb,
      ram_used_pct: vps.ram_total_mb ? Math.round(((vps.ram_used_mb ?? 0) / vps.ram_total_mb) * 100) : null,
      disk_free_gb: vps.disk_free_gb,
      tunnel_clients: vps.tunnel_clients,
      open_tunnels: vps.open_tunnels,
      players_connected: vps.players_connected,
      uptime_seconds: vps.uptime_seconds,
    }
    : null;

  const release = releaseRes.data as any;

  return {
    ok: true,
    time: new Date().toISOString(),
    api: { name: API_NAME, version: API_VERSION },
    maintenance: maintenance
      ? { active: !!maintenance.active, reason: maintenance.reason ?? null }
      : { active: false, reason: null },
    servers: { total: serversTotal, players_online: playersOnline },
    extensions: { published: extCountRes.count ?? 0 },
    vps: vpsBlocks,
    release: release
      ? {
        version_code: release.version_code,
        version_name: release.version_name,
        channel: release.channel,
        published_at: release.created_at,
      }
      : null,
  };
}

function statusPageHtml(status: any): string {
  const esc = (v: unknown) => String(v ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c] as string));
  // same design language as the website (tokens, Roboto Flex, top app bar,
  // cards, status badges) so the api domain does not look like a different product.
  const maintenance = status.maintenance?.active
    ? `<div class="banner">Maintenance active${status.maintenance.reason ? ": " + esc(status.maintenance.reason) : ""}</div>`
    : "";
  const vps = status.vps
    ? `<tr><td>tunnel server</td><td>${status.vps.stale ? "stale" : "ok"} · load ${esc(status.vps.load1)} · RAM ${esc(status.vps.ram_used_pct)}% · ${esc(status.vps.open_tunnels)} tunnels</td></tr>`
    : "";
  const release = status.release
    ? `<tr><td>latest release</td><td>${esc(status.release.version_name)} (${esc(status.release.version_code)})</td></tr>`
    : "";
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(API_NAME)}</title>
<link rel="icon" type="image/png" href="https://host.kodanetwork.eu/assets/logo.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Roboto+Flex:opsz,wght@8..144,300..700&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
<style>
:root{--md-surface:#1B1613;--md-surface-container:#231D18;--md-surface-container-high:#2D2620;--md-on-surface:#EFE0D9;--md-on-surface-variant:#D8C2B5;--md-outline-variant:#534339;--brand:#FF6B00;--brand-container:rgba(255,107,0,.15);--md-success:#6FBE7E;--md-success-container:rgba(111,190,126,.15);--md-error:#FFB4AB;--md-error-container:rgba(255,180,171,.12);--shape-md:12px;--shape-lg:16px;--shape-full:999px;--elev-1:0 1px 2px rgba(0,0,0,.3),0 1px 3px 1px rgba(0,0,0,.15)}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Roboto Flex','Roboto',sans-serif;background:var(--md-surface);color:var(--md-on-surface);-webkit-font-smoothing:antialiased;min-height:100vh}
a{color:inherit;text-decoration:none}
.top-app-bar{display:flex;align-items:center;justify-content:space-between;padding:12px 20px;background:var(--md-surface-container);box-shadow:var(--elev-1)}
.logo{display:flex;align-items:center;gap:10px;font-weight:500;font-size:1.0625rem}
.logo img{width:32px;height:32px;border-radius:8px}
.logo .b{color:var(--brand)}
.top-app-bar nav{display:flex;gap:18px;font-size:.875rem;color:var(--md-on-surface-variant)}
.top-app-bar nav a:hover{color:var(--brand)}
main{max-width:760px;margin:0 auto;padding:36px 20px 64px}
.head{display:flex;align-items:center;gap:14px;flex-wrap:wrap}
h1{font-size:1.75rem;font-weight:600;letter-spacing:-.01em}
h2{font-size:1rem;font-weight:600;margin-bottom:12px}
.sub{color:var(--md-on-surface-variant);margin-top:10px;line-height:1.55;max-width:64ch}
.status-badge{display:inline-flex;align-items:center;gap:6px;padding:4px 12px;border-radius:var(--shape-sm,8px);font-size:.6875rem;font-weight:500;text-transform:uppercase;letter-spacing:.04em;white-space:nowrap}
.status-badge::before{content:'';width:6px;height:6px;border-radius:var(--shape-full);background:currentColor}
.status-online{background:var(--md-success-container);color:var(--md-success)}
.status-error{background:var(--md-error-container);color:var(--md-error)}
.banner{margin-top:16px;padding:12px 16px;border-radius:var(--shape-md);background:var(--brand-container);color:var(--brand);font-size:.875rem}
.card{background:var(--md-surface-container);border-radius:var(--shape-lg);box-shadow:var(--elev-1);padding:22px;margin-top:20px}
table{border-collapse:collapse;width:100%}
td{padding:9px 0;border-bottom:1px solid var(--md-outline-variant);vertical-align:top;font-size:.9375rem}
tr:last-child td{border-bottom:0}
td:first-child{color:var(--md-on-surface-variant);width:38%}
code{font-family:'JetBrains Mono',ui-monospace,monospace;font-size:.8125rem;background:var(--md-surface-container-high);padding:3px 8px;border-radius:8px;color:var(--brand)}
.ep{margin-top:14px;display:flex;flex-wrap:wrap;gap:8px}
.foot{color:var(--md-on-surface-variant);font-size:.75rem;margin-top:28px;text-align:center;line-height:1.6}
.foot a{color:var(--brand)}
</style></head><body>
<header class="top-app-bar">
<a href="https://host.kodanetwork.eu" class="logo"><img src="https://host.kodanetwork.eu/assets/logo.png" alt="KodaHosting logo">Koda<span class="b">Hosting</span></a>
<nav><a href="https://host.kodanetwork.eu/dashboard">Dashboard</a><a href="https://host.kodanetwork.eu/docs">Docs</a><a href="https://host.kodanetwork.eu/store">Store</a></nav>
</header>
<main>
<div class="head">
<h1>${esc(API_NAME)}</h1>
<span class="status-badge ${status.maintenance?.active ? "status-error" : "status-online"}">${status.maintenance?.active ? "maintenance" : "operational"} · ${esc(status.time)}</span>
</div>
<p class="sub">Live values for the network: servers and players, published extensions, the tunnel server and the latest app release. Everything on this domain is read-only unless a bearer token is sent.</p>
${maintenance}
<div class="card">
<table>
<tr><td>servers</td><td>${esc(status.servers.total)} · ${esc(status.servers.players_online)} players online</td></tr>
<tr><td>extensions</td><td>${esc(status.extensions.published)} published</td></tr>
${vps}
${release}
</table>
</div>
<div class="card">
<h2>Endpoints</h2>
<div class="ep"><code>GET /v1/status</code><code>GET /v1/releases</code><code>GET /v1/extensions</code><code>GET /v1/servers</code><code>GET /v1/invite/{token}</code></div>
<div class="ep"><code>GET /v1/extensions/{id}</code><code>GET /v1/extensions/{id}/download</code></div>
<div class="ep"><code>GET /v1/me</code><code>GET /v1/me/extensions</code><code>GET /v1/me/tickets</code></div>
<div class="ep"><code>POST /v1/extensions</code><code>POST /v1/extensions/{id}/finalize</code></div>
</div>
<p class="foot">Powered by <a href="https://host.kodanetwork.eu">KodaHosting</a>. Not an official Minecraft product, not approved by or associated with Mojang or Microsoft.</p>
</main>
</body></html>`;
}

// ---------------------------------------------------------------------------
// store
// ---------------------------------------------------------------------------

async function listExtensions(admin: Admin, url: URL, cors: Record<string, string>) {
  const search = (url.searchParams.get("q") ?? "").replace(/[%_,()\\]/g, "").slice(0, 64);
  const category = url.searchParams.get("category") ?? "";
  const author = (url.searchParams.get("author") ?? "").trim().slice(0, 64);

  let query: any = admin
    .from("extensions")
    .select("id,ext_id,name,summary,category,icon_url,author,license,downloads,published_at,updated_at")
    .eq("status", "APPROVED")
    .order("updated_at", { ascending: false })
    .limit(200);
  if (search) query = query.or(`name.ilike.%${search}%,summary.ilike.%${search}%`);
  if (CATEGORIES.includes(category)) query = query.eq("category", category);
  if (author) query = query.eq("author", author);

  const { data: exts, error } = await query;
  if (error) return fail("internal_error", 500, cors);
  const rows = (exts ?? []) as any[];
  if (rows.length === 0) return json({ ok: true, items: [] }, 200, cors);

  const { data: vers } = await admin
    .from("extension_versions")
    .select("extension_id,version,version_code,min_app_version,changelog,sha256,file_size,reviewed_at")
    .in("extension_id", rows.map((e) => e.id))
    .eq("status", "APPROVED")
    .order("version_code", { ascending: false });
  const latest = new Map<string, any>();
  for (const v of (vers ?? []) as any[]) {
    if (!latest.has(v.extension_id)) latest.set(v.extension_id, v);
  }

  const items = rows
    .filter((e) => latest.has(e.id))
    .map((e) => {
      const v = latest.get(e.id);
      return {
        ext_id: e.ext_id,
        name: e.name,
        summary: e.summary,
        category: e.category,
        icon_url: e.icon_url,
        author: e.author,
        license: e.license,
        downloads: Number(e.downloads ?? 0),
        updated_at: e.updated_at,
        latest_version: {
          version: v.version,
          version_code: v.version_code,
          min_app_version: v.min_app_version,
          changelog: v.changelog,
          sha256: v.sha256,
          file_size: v.file_size,
          released_at: v.reviewed_at,
        },
      };
    });
  return json({ ok: true, items }, 200, cors);
}

async function getExtension(admin: Admin, extId: string, cors: Record<string, string>) {
  const { data: ext } = await admin
    .from("extensions")
    .select("id,ext_id,name,summary,description,category,icon_url,screenshots,author,license,homepage_url,source_url,downloads,published_at,updated_at")
    .eq("ext_id", extId)
    .eq("status", "APPROVED")
    .maybeSingle();
  if (!ext) return fail("not_found", 404, cors);

  const { data: vers } = await admin
    .from("extension_versions")
    .select("version,version_code,min_app_version,changelog,permissions,sha256,file_size,reviewed_at")
    .eq("extension_id", (ext as any).id)
    .eq("status", "APPROVED")
    .order("version_code", { ascending: false });

  return json({
    ok: true,
    extension: {
      ext_id: (ext as any).ext_id,
      name: (ext as any).name,
      summary: (ext as any).summary,
      description: (ext as any).description,
      category: (ext as any).category,
      icon_url: (ext as any).icon_url,
      screenshots: (ext as any).screenshots ?? [],
      author: (ext as any).author,
      license: (ext as any).license,
      homepage_url: (ext as any).homepage_url,
      source_url: (ext as any).source_url,
      downloads: Number((ext as any).downloads ?? 0),
      published_at: (ext as any).published_at,
      updated_at: (ext as any).updated_at,
      versions: (vers ?? []).map((v: any) => ({
        version: v.version,
        version_code: v.version_code,
        min_app_version: v.min_app_version,
        changelog: v.changelog,
        permissions: v.permissions ?? [],
        sha256: v.sha256,
        file_size: v.file_size,
        released_at: v.reviewed_at,
      })),
    },
  }, 200, cors);
}

async function downloadExtension(admin: Admin, extId: string, url: URL, cors: Record<string, string>) {
  const { data: ext } = await admin
    .from("extensions").select("id").eq("ext_id", extId).eq("status", "APPROVED").maybeSingle();
  if (!ext) return fail("not_found", 404, cors);

  const { data: vers } = await admin
    .from("extension_versions")
    .select("version,version_code,file_path")
    .eq("extension_id", (ext as any).id)
    .eq("status", "APPROVED")
    .order("version_code", { ascending: false });
  const all = (vers ?? []) as any[];
  if (all.length === 0) return fail("not_found", 404, cors);

  const wanted = url.searchParams.get("version");
  let row = all[0];
  if (wanted && wanted !== "latest") {
    const match = all.find((v) => String(v.version_code) === wanted || v.version === wanted);
    if (!match) return fail("not_found", 404, cors);
    row = match;
  }

  const { data: signed, error } = await admin.storage
    .from("extension_packages")
    .createSignedUrl(row.file_path, 60);
  if (error || !signed?.signedUrl) return fail("internal_error", 500, cors);

  // fire and forget: the counter must not hold up the download
  admin.rpc("fn_ext_bump_downloads", { p_ext_id: extId }).then(() => {}, () => {});

  return new Response(null, {
    status: 302,
    headers: { ...cors, "Location": signed.signedUrl, "Cache-Control": "no-store" },
  });
}

/** shared metadata validation for POST /v1/extensions and the versions endpoint. */
function readSubmission(body: any) {
  const extId = str(body.ext_id, 64);
  const name = str(body.name, 80);
  const version = str(body.version, 24);
  const versionCode = intIn(body.version_code, 1, 1_000_000_000);
  const license = str(body.license, 64);
  if (!extId || !ID_PATTERN.test(extId)) return { error: "invalid_ext_id" };
  if (!name) return { error: "invalid_name" };
  if (!version || !VERSION_PATTERN.test(version)) return { error: "invalid_version" };
  if (versionCode == null) return { error: "invalid_version_code" };
  if (!license) return { error: "invalid_license" };

  const sha = optStr(body.sha256, 64);
  const fileSize = body.file_size == null ? null : intIn(body.file_size, 1, MAX_PACKAGE_BYTES);
  if (sha && !/^[0-9a-f]{64}$/.test(sha.toLowerCase())) return { error: "invalid_sha256" };
  if (body.file_size != null && fileSize == null) return { error: "invalid_file_size" };

  return {
    meta: {
      ext_id: extId,
      name,
      summary: optStr(body.summary, 200),
      description: optStr(body.description, 6000),
      category: CATEGORIES.includes(body.category) ? body.category : "other",
      license,
      author: optStr(body.author, 64),
      homepage_url: httpUrl(body.homepage_url),
      source_url: httpUrl(body.source_url),
      icon_url: httpUrl(body.icon_url, 500),
      screenshots: Array.isArray(body.screenshots)
        ? body.screenshots.map((s: unknown) => httpUrl(s, 500)).filter(Boolean).slice(0, 6)
        : [],
    },
    version: {
      version,
      version_code: versionCode,
      min_app_version: intIn(body.min_app_version ?? 0, 0, 1_000_000_000) ?? 0,
      changelog: optStr(body.changelog, 4000),
      permissions: cleanPermissions(body.permissions),
      sha256: sha ? sha.toLowerCase() : null,
      file_size: fileSize,
    },
  };
}

async function pendingCount(admin: Admin, authId: string): Promise<number> {
  const { count } = await admin
    .from("extension_versions")
    .select("id, extensions!inner(owner_auth_id)", { count: "exact", head: true })
    .eq("status", "PENDING")
    .eq("extensions.owner_auth_id", authId);
  return count ?? 0;
}

async function uploadTarget(admin: Admin, extId: string, versionCode: number) {
  const filePath = `${extId}/${versionCode}.kodaext`;
  const { data, error } = await admin.storage.from("extension_packages").createSignedUploadUrl(filePath);
  if (error || !data) return null;
  return { path: filePath, signed_url: data.signedUrl, token: data.token };
}

async function submitExtension(admin: Admin, user: { id: string }, body: any, cors: Record<string, string>) {
  const parsed = readSubmission(body);
  if (parsed.error) return fail(parsed.error, 400, cors);
  const meta = parsed.meta!;
  const version = parsed.version!;

  if ((await pendingCount(admin, user.id)) >= MAX_PENDING_SUBMISSIONS) {
    return fail("too_many_pending_submissions", 429, cors);
  }

  const { data: existing } = await admin
    .from("extensions").select("id,owner_auth_id").eq("ext_id", meta.ext_id).maybeSingle();
  if (existing) {
    return fail((existing as any).owner_auth_id === user.id ? "already_submitted" : "id_taken", 409, cors);
  }

  const { data: ext, error: extErr } = await admin
    .from("extensions")
    .insert({ ...meta, owner_auth_id: user.id, status: "PENDING" })
    .select("id")
    .single();
  if (extErr || !ext) return fail("internal_error", 500, cors);

  const upload = await uploadTarget(admin, meta.ext_id, version.version_code);
  if (!upload) {
    await admin.from("extensions").delete().eq("id", (ext as any).id);
    return fail("internal_error", 500, cors);
  }

  const { error: verErr } = await admin.from("extension_versions").insert({
    extension_id: (ext as any).id,
    version: version.version,
    version_code: version.version_code,
    min_app_version: version.min_app_version,
    changelog: version.changelog,
    permissions: version.permissions,
    file_path: upload.path,
    file_size: version.file_size,
    sha256: version.sha256,
    status: "PENDING",
  });
  if (verErr) {
    await admin.from("extensions").delete().eq("id", (ext as any).id);
    return fail("internal_error", 500, cors);
  }

  return json({
    ok: true,
    extension_id: (ext as any).id,
    ext_id: meta.ext_id,
    version_code: version.version_code,
    upload: { signed_url: upload.signed_url, token: upload.token, path: upload.path },
  }, 200, cors);
}

async function addVersion(admin: Admin, user: { id: string }, extId: string, body: any, cors: Record<string, string>) {
  const parsed = readSubmission({ ...body, ext_id: extId });
  if (parsed.error) return fail(parsed.error, 400, cors);
  const meta = parsed.meta!;
  const version = parsed.version!;

  const { data: ext } = await admin
    .from("extensions").select("id,owner_auth_id,status,review_note").eq("ext_id", extId).maybeSingle();
  if (!ext) return fail("not_found", 404, cors);
  if ((ext as any).owner_auth_id !== user.id) return fail("forbidden", 403, cors);
  if ((await pendingCount(admin, user.id)) >= MAX_PENDING_SUBMISSIONS) {
    return fail("too_many_pending_submissions", 429, cors);
  }

  const { data: maxRow } = await admin
    .from("extension_versions").select("version_code")
    .eq("extension_id", (ext as any).id)
    .order("version_code", { ascending: false }).limit(1).maybeSingle();
  if (maxRow && version.version_code <= (maxRow as any).version_code) {
    return fail("version_code_too_low", 409, cors);
  }

  const upload = await uploadTarget(admin, extId, version.version_code);
  if (!upload) return fail("internal_error", 500, cors);

  const { error } = await admin.from("extension_versions").insert({
    extension_id: (ext as any).id,
    version: version.version,
    version_code: version.version_code,
    min_app_version: version.min_app_version,
    changelog: version.changelog,
    permissions: version.permissions,
    file_path: upload.path,
    file_size: version.file_size,
    sha256: version.sha256,
    status: "PENDING",
  });
  if (error) {
    // one submission in flight per extension (partial unique index)
    if (String((error as any).code) === "23505") return fail("pending_version_exists", 409, cors);
    return fail("internal_error", 500, cors);
  }

  // a fresh submission re-enters the review, name/summary may have changed as well
  const touch: Record<string, unknown> = {
    updated_at: new Date().toISOString(),
    name: meta.name,
    summary: meta.summary,
    description: meta.description,
    category: meta.category,
    license: meta.license,
    author: meta.author,
    homepage_url: meta.homepage_url,
    source_url: meta.source_url,
    icon_url: meta.icon_url,
    screenshots: meta.screenshots,
  };
  if (["WITHDRAWN", "REJECTED", "DELISTED"].includes(String((ext as any).status))) {
    touch.status = "PENDING";
    touch.review_note = null;
    touch.ended_at = null;
  }
  await admin.from("extensions").update(touch).eq("id", (ext as any).id);

  return json({
    ok: true,
    ext_id: extId,
    version_code: version.version_code,
    upload: { signed_url: upload.signed_url, token: upload.token, path: upload.path },
  }, 200, cors);
}

async function finalizeVersion(admin: Admin, user: { id: string }, extId: string, body: any, cors: Record<string, string>) {
  const versionCode = intIn(body.version_code, 1, 1_000_000_000);
  if (versionCode == null) return fail("invalid_version_code", 400, cors);

  const { data: ext } = await admin
    .from("extensions").select("id,owner_auth_id").eq("ext_id", extId).maybeSingle();
  if (!ext) return fail("not_found", 404, cors);
  if ((ext as any).owner_auth_id !== user.id) return fail("forbidden", 403, cors);

  const { data: ver } = await admin
    .from("extension_versions")
    .select("*")
    .eq("extension_id", (ext as any).id)
    .eq("version_code", versionCode)
    .eq("status", "PENDING")
    .maybeSingle();
  if (!ver) return fail("not_found", 404, cors);
  const row = ver as any;

  const cleanup = async (reason: string, status: number) => {
    await admin.from("extension_versions").delete().eq("id", row.id);
    await admin.storage.from("extension_packages").remove([row.file_path]);
    return fail(reason, status, cors);
  };

  const { data: blob, error: dlErr } = await admin.storage.from("extension_packages").download(row.file_path);
  if (dlErr || !blob) return cleanup("upload_missing", 400, cors);
  if (blob.size > MAX_PACKAGE_BYTES) return cleanup("package_too_large", 400, cors);

  const bytes = await blob.arrayBuffer();
  const head = new Uint8Array(bytes, 0, Math.min(4, bytes.byteLength));
  if (head[0] !== 0x50 || head[1] !== 0x4b) return cleanup("not_a_zip", 400, cors);

  const digest = await sha256Hex(bytes);
  if (row.sha256 && row.sha256.toLowerCase() !== digest) return cleanup("sha256_mismatch", 400, cors);
  if (row.file_size && Number(row.file_size) !== bytes.byteLength) return cleanup("file_size_mismatch", 400, cors);

  // only keep the manifest, everything else stays compressed (memory stays tiny)
  let manifest: any = null;
  try {
    const files = unzipSync(new Uint8Array(bytes), {
      filter: (f) => f.name === "manifest.json" || /^[^/]+\/manifest\.json$/.test(f.name),
    });
    const key = Object.keys(files).find((k) => k === "manifest.json")
      ?? Object.keys(files).find((k) => k.endsWith("/manifest.json"));
    if (key) manifest = JSON.parse(new TextDecoder().decode(files[key]));
  } catch (e) {
    return cleanup("invalid_package", 400, cors);
  }
  if (!manifest || typeof manifest !== "object") return cleanup("manifest_missing", 400, cors);
  if (typeof manifest.id !== "string" || manifest.id !== extId) return cleanup("manifest_id_mismatch", 400, cors);
  if (Number(manifest.versionCode) !== versionCode) return cleanup("manifest_version_code_mismatch", 400, cors);
  if (typeof manifest.license !== "string" || !manifest.license.trim()) return cleanup("manifest_license_missing", 400, cors);
  if (manifest.schema != null && Number(manifest.schema) !== 1) return cleanup("manifest_schema_unsupported", 400, cors);
  const perms = Array.isArray(manifest.permissions) ? manifest.permissions : [];
  for (const p of perms) {
    if (typeof p !== "string" || !BASE_PERMISSIONS.has(p)) return cleanup("manifest_permission_unknown", 400, cors);
  }

  const { error: upErr } = await admin.from("extension_versions")
    .update({ file_size: bytes.byteLength, sha256: digest, verified_at: new Date().toISOString() })
    .eq("id", row.id);
  if (upErr) return fail("internal_error", 500, cors);
  await admin.from("extensions").update({ updated_at: new Date().toISOString() }).eq("id", (ext as any).id);

  return json({ ok: true, verified: true, sha256: digest, file_size: bytes.byteLength }, 200, cors);
}

async function withdrawExtension(admin: Admin, user: { id: string }, extId: string, cors: Record<string, string>) {
  const { data: ext } = await admin
    .from("extensions").select("id,owner_auth_id,status").eq("ext_id", extId).maybeSingle();
  if (!ext) return fail("not_found", 404, cors);
  if ((ext as any).owner_auth_id !== user.id) return fail("forbidden", 403, cors);
  if ((ext as any).status === "WITHDRAWN") return json({ ok: true, status: "WITHDRAWN" }, 200, cors);

  const { error } = await admin.from("extensions")
    .update({ status: "WITHDRAWN", ended_at: new Date().toISOString(), updated_at: new Date().toISOString() })
    .eq("id", (ext as any).id);
  if (error) return fail("internal_error", 500, cors);
  return json({ ok: true, status: "WITHDRAWN" }, 200, cors);
}

// ---------------------------------------------------------------------------
// account
// ---------------------------------------------------------------------------

async function getMe(admin: Admin, user: { id: string; email: string | null; created_at: string | null }, cors: Record<string, string>) {
  const [profileRes, avatarRes, uuids] = await Promise.all([
    admin.from("koda_users")
      .select("app_uuid,nickname,mc_username,mc_uuid,is_main,two_fa_enabled,created_at")
      .eq("auth_id", user.id)
      .order("is_main", { ascending: false })
      .order("created_at", { ascending: true })
      .limit(1)
      .maybeSingle(),
    admin.from("account_profiles").select("avatar_url").eq("auth_id", user.id).maybeSingle(),
    appUuidsFor(admin, user.id),
  ]);
  const profile = profileRes.data as any;

  let serverCount = 0;
  if (uuids.length > 0) {
    const { count } = await admin.from("koda_servers")
      .select("id", { count: "exact", head: true })
      .in("owner_app_uuid", uuids)
      .not("host", "like", "deleted_%");
    serverCount = count ?? 0;
  }
  const { count: extCount } = await admin.from("extensions")
    .select("id", { count: "exact", head: true }).eq("owner_auth_id", user.id);

  return json({
    ok: true,
    me: {
      email: user.email,
      member_since: user.created_at,
      nickname: profile?.nickname ?? null,
      mc_username: profile?.mc_username ?? null,
      mc_uuid: profile?.mc_uuid ?? null,
      two_fa: !!profile?.two_fa_enabled,
      linked_since: profile?.created_at ?? null,
      main_app_uuid: profile?.app_uuid ?? null,
      avatar_url: (avatarRes.data as any)?.avatar_url ?? null,
      device_count: uuids.length,
      server_count: serverCount,
      extension_count: extCount ?? 0,
    },
  }, 200, cors);
}

async function patchMe(admin: Admin, user: { id: string; email: string | null }, body: any, cors: Record<string, string>) {
  const hasNickname = Object.prototype.hasOwnProperty.call(body, "nickname");
  const hasAvatar = Object.prototype.hasOwnProperty.call(body, "avatar_url");
  if (!hasNickname && !hasAvatar) return fail("nothing_to_update", 400, cors);

  if (hasNickname) {
    const nickname = str(body.nickname, 24);
    if (!nickname || nickname.length < 2) return fail("invalid_nickname", 400, cors);
    const { data: updated, error } = await admin.from("koda_users")
      .update({ nickname })
      .eq("auth_id", user.id)
      .select("app_uuid");
    if (error) return fail("internal_error", 500, cors);
    if (!updated || updated.length === 0) return fail("account_not_linked", 404, cors);
  }

  if (hasAvatar) {
    const avatarUrl = body.avatar_url == null ? null : str(body.avatar_url, 500);
    const publicPrefix = `${Deno.env.get("SUPABASE_URL") ?? ""}/storage/v1/object/public/avatars/`;
    if (avatarUrl && !avatarUrl.startsWith(publicPrefix)) return fail("invalid_avatar_url", 400, cors);
    const { error } = await admin.from("account_profiles")
      .upsert({ auth_id: user.id, avatar_url: avatarUrl, updated_at: new Date().toISOString() });
    if (error) return fail("internal_error", 500, cors);
  }

  return json({ ok: true }, 200, cors);
}

async function uploadAvatar(admin: Admin, user: { id: string }, body: any, cors: Record<string, string>) {
  const type = str(body.content_type, 32);
  const ext = type === "image/png" ? "png" : type === "image/jpeg" ? "jpg" : null;
  if (!ext) return fail("invalid_content_type", 400, cors);

  const path = `${user.id}/avatar-${Date.now()}.${ext}`;
  const { data, error } = await admin.storage.from("avatars").createSignedUploadUrl(path);
  if (error || !data) return fail("internal_error", 500, cors);

  return json({
    ok: true,
    upload: { signed_url: data.signedUrl, token: data.token, path: data.path },
    public_url: `${Deno.env.get("SUPABASE_URL") ?? ""}/storage/v1/object/public/avatars/${path}`,
  }, 200, cors);
}

// icons and store screenshots: same signed-upload pattern, but into the public
// extension_assets bucket (5 MB, png/jpeg/webp). the path carries the auth id, so
// nobody can overwrite someone else's images. (upload rate: RATE_ASSET_UPLOADS.)

async function uploadAsset(admin: Admin, user: { id: string }, body: any, cors: Record<string, string>) {
  const type = str(body.content_type, 32);
  const ext = type === "image/png" ? "png" : type === "image/jpeg" ? "jpg" : type === "image/webp" ? "webp" : null;
  if (!ext) return fail("invalid_content_type", 400, cors);
  const retry = await rateTake(admin, `asset:${user.id}`, RATE_ASSET_UPLOADS);
  if (retry > 0) return tooMany(retry, cors);

  const rand = Math.random().toString(36).slice(2, 6);
  const path = `${user.id}/${Date.now()}-${rand}.${ext}`;
  const { data, error } = await admin.storage.from("extension_assets").createSignedUploadUrl(path);
  if (error || !data) return fail("internal_error", 500, cors);

  return json({
    ok: true,
    upload: { signed_url: data.signedUrl, token: data.token, path: data.path },
    public_url: `${Deno.env.get("SUPABASE_URL") ?? ""}/storage/v1/object/public/extension_assets/${path}`,
  }, 200, cors);
}

async function deleteAvatar(admin: Admin, user: { id: string }, cors: Record<string, string>) {
  const { data: files } = await admin.storage.from("avatars").list(user.id);
  if (files && files.length > 0) {
    await admin.storage.from("avatars").remove(files.map((f: any) => `${user.id}/${f.name}`));
  }
  await admin.from("account_profiles")
    .upsert({ auth_id: user.id, avatar_url: null, updated_at: new Date().toISOString() });
  return json({ ok: true }, 200, cors);
}

/**
 * the account's devices: koda_users rows share an app_uuid per device, so the
 * rows are grouped by it. telemetry (model, battery, app version) comes from the
 * newest ping of the device, accounts lists the MC accounts living on it.
 */
async function myDevices(admin: Admin, user: { id: string }, cors: Record<string, string>) {
  const { data } = await admin
    .from("koda_users")
    .select("app_uuid,nickname,mc_username,is_main,device_model,os_version,app_version,app_state,app_last_ping,battery_level,is_charging,network_type,total_ram_mb,free_ram_mb,created_at")
    .eq("auth_id", user.id)
    .order("created_at", { ascending: true });

  const byDevice = new Map<string, any>();
  for (const row of (data ?? []) as any[]) {
    const key = String(row.app_uuid);
    if (!byDevice.has(key)) {
      byDevice.set(key, {
        app_uuid: key,
        model: row.device_model ?? null,
        os: row.os_version ?? null,
        app_version: row.app_version ?? null,
        app_state: row.app_state ?? null,
        last_ping: row.app_last_ping ?? null,
        battery: row.battery_level ?? null,
        charging: row.is_charging ?? null,
        network: row.network_type ?? null,
        total_ram_mb: row.total_ram_mb ?? null,
        free_ram_mb: row.free_ram_mb ?? null,
        first_seen: row.created_at ?? null,
        accounts: [],
      });
    }
    const device = byDevice.get(key);
    device.accounts.push({
      mc_username: row.mc_username ?? null,
      nickname: row.nickname ?? null,
      is_main: !!row.is_main,
    });
    // the newest ping wins for the device level telemetry
    if (row.app_last_ping && (!device.last_ping || row.app_last_ping > device.last_ping)) {
      device.last_ping = row.app_last_ping;
      device.model = row.device_model ?? device.model;
      device.os = row.os_version ?? device.os;
      device.app_version = row.app_version ?? device.app_version;
      device.app_state = row.app_state ?? device.app_state;
      device.battery = row.battery_level ?? device.battery;
      device.charging = row.is_charging ?? device.charging;
      device.network = row.network_type ?? device.network;
      device.total_ram_mb = row.total_ram_mb ?? device.total_ram_mb;
      device.free_ram_mb = row.free_ram_mb ?? device.free_ram_mb;
    }
  }

  const items = Array.from(byDevice.values());
  return json({ ok: true, items }, 200, cors);
}

/** detach a device from the account; the app keeps working standalone and can sign in again. */
async function unlinkDevice(admin: Admin, user: { id: string }, appUuid: string, cors: Record<string, string>) {
  const { data: rows } = await admin
    .from("koda_users")
    .select("id")
    .eq("app_uuid", appUuid)
    .eq("auth_id", user.id);
  if (!rows || rows.length === 0) return fail("not_found", 404, cors);

  const { error } = await admin
    .from("koda_users")
    .update({ auth_id: null })
    .eq("app_uuid", appUuid)
    .eq("auth_id", user.id);
  if (error) return fail("internal_error", 500, cors);
  return json({ ok: true }, 200, cors);
}

async function myExtensions(admin: Admin, user: { id: string }, cors: Record<string, string>) {  const { data } = await admin
    .from("extensions")
    .select("ext_id,name,summary,category,status,review_note,downloads,created_at,updated_at,published_at,ended_at,extension_versions(version,version_code,status,review_note,verified_at,reviewed_at,file_size)")
    .eq("owner_auth_id", user.id)
    .order("updated_at", { ascending: false });
  const items = ((data ?? []) as any[]).map((e) => ({
    ...e,
    downloads: Number(e.downloads ?? 0),
    versions: (e.extension_versions ?? []).sort((a: any, b: any) => b.version_code - a.version_code),
    extension_versions: undefined,
  }));
  return json({ ok: true, items }, 200, cors);
}

// ---------------------------------------------------------------------------
// public server list (community directory) plus a per-server detail with a live
// minecraft ping. host, motd, players and the OWNER NAME (when the owner linked
// an MC account) are public - owner data is exactly what the v_servers_public
// view allowed, plus the linked mc_username/nickname. never tokens, hwids or app
// uuids.
// ---------------------------------------------------------------------------

const SERVER_PUBLIC_COLUMNS =
  "host,base_domain,server_version,online_players,max_players,motd,gamemode,difficulty,pvp,whitelist,last_online,ram_mb,is_banned,owner_app_uuid";

function serverStatus(row: any) {
  const raw = String(row.server_version ?? "").trim();
  const online = !!raw && raw !== "HIBERNATED" && raw !== "DELETED"
    && !raw.startsWith("OFFLINE") && !raw.startsWith("CMD:");
  return {
    status: online ? "ONLINE" : raw === "HIBERNATED" ? "HIBERNATED" : "OFFLINE",
    version: online ? raw.replace(/^OFFLINE\|/, "").split(" | ")[0].trim() : "",
  };
}

/** the server owner, but only when that account linked a minecraft username. */
async function ownerFor(admin: Admin, ownerAppUuid: string | null | undefined) {
  if (!ownerAppUuid) return null;
  const { data } = await admin.from("koda_users")
    .select("mc_username,nickname")
    .eq("app_uuid", String(ownerAppUuid))
    .not("mc_username", "is", null)
    .order("is_main", { ascending: false })
    .limit(1);
  const row = (data ?? [])[0] as any;
  if (!row?.mc_username) return null;
  return { mc_username: row.mc_username, nickname: row.nickname ?? null };
}

async function ownersForUuids(admin: Admin, uuids: string[]) {
  const map = new Map<string, { mc_username: string; nickname: string | null }>();
  if (uuids.length === 0) return map;
  const { data } = await admin.from("koda_users")
    .select("app_uuid,mc_username,nickname")
    .in("app_uuid", uuids)
    .not("mc_username", "is", null);
  for (const row of (data ?? []) as any[]) {
    const key = String(row.app_uuid);
    if (!map.has(key)) map.set(key, { mc_username: row.mc_username, nickname: row.nickname ?? null });
  }
  return map;
}

function mapServerRow(row: any, owner: { mc_username: string; nickname: string | null } | null) {
  const state = serverStatus(row);
  return {
    host: row.host,
    domain: row.base_domain || "kodanetwork.eu",
    status: state.status,
    version: state.version,
    players_online: Number(row.online_players ?? 0) || 0,
    players_max: Number(row.max_players ?? 0) || 0,
    motd: row.motd ?? "",
    gamemode: row.gamemode ?? "",
    difficulty: row.difficulty ?? "",
    last_online: row.last_online,
    owner,
  };
}

async function publicServers(admin: Admin, url: URL, cors: Record<string, string>) {
  const onlineOnly = url.searchParams.get("online_only") === "1";
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 100) || 100, 1), 200);

  const { data, error } = await admin
    .from("koda_servers")
    .select(SERVER_PUBLIC_COLUMNS)
    .not("host", "like", "deleted_%")
    .or("is_banned.is.null,is_banned.eq.false")
    .limit(2000);
  if (error) return fail("internal_error", 500, cors);

  const rows = (data ?? []) as any[];
  const owners = await ownersForUuids(
    admin,
    [...new Set(rows.map((r) => r.owner_app_uuid).filter(Boolean).map(String))],
  );

  const items = rows
    .map((row) => mapServerRow(row, owners.get(String(row.owner_app_uuid)) ?? null))
    .filter((s) => !onlineOnly || s.status === "ONLINE")
    .sort((a, b) => (b.status === "ONLINE" ? 1 : 0) - (a.status === "ONLINE" ? 1 : 0)
      || b.players_online - a.players_online)
    .slice(0, limit);

  return json({ ok: true, count: items.length, items }, 200, cors);
}

// ---------------------------------------------------------------------------
// minecraft server list ping (the "pinging tool"): a raw tcp status request to
// the tunneled port, parsed down to players, motd, version and the favicon data
// uri the server advertises. results are cached for a minute per warm instance.
// ---------------------------------------------------------------------------

function varIntBytes(value: number): number[] {
  const out: number[] = [];
  let v = value >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    out.push(b);
  } while (v !== 0);
  return out;
}

function flattenMotd(description: unknown): string {
  if (description == null) return "";
  if (typeof description === "string") return description;
  if (typeof description !== "object") return String(description);
  const node = description as any;
  let text = typeof node.text === "string" ? node.text : "";
  if (Array.isArray(node.extra)) {
    for (const part of node.extra) text += flattenMotd(part);
  }
  return text;
}

async function pingMinecraft(hostname: string, port: number, timeoutMs = 2500): Promise<any> {
  const started = Date.now();
  let conn: Deno.Conn | null = null;
  let timerId: number | undefined;
  try {
    const timeout = new Promise<never>((_, reject) => {
      timerId = setTimeout(() => reject(new Error("timeout")), timeoutMs);
    });
    conn = await Promise.race([Deno.connect({ hostname, port }), timeout]);

    const hostBytes = new TextEncoder().encode(hostname);
    const handshake = [
      0x00,
      ...varIntBytes(767),
      ...varIntBytes(hostBytes.length),
      ...hostBytes,
      (port >>> 8) & 0xff,
      port & 0xff,
      0x01,
    ];
    const statusRequest = [0x01, 0x00];
    await conn.write(new Uint8Array([...varIntBytes(handshake.length), ...handshake, ...statusRequest]));

    let buffer = new Uint8Array(0);
    const parsePacket = (): Uint8Array | null => {
      let value = 0, shift = 0, index = 0;
      while (index < buffer.length) {
        const b = buffer[index++];
        value |= (b & 0x7f) << shift;
        if ((b & 0x80) === 0) break;
        shift += 7;
        if (shift > 35) return null;
      }
      if (index === 0 || index > buffer.length || (buffer[index - 1] & 0x80) !== 0) return null;
      if (buffer.length < index + value) return null;
      return buffer.subarray(index, index + value);
    };

    let payload: Uint8Array | null = null;
    for (let i = 0; i < 60 && !payload; i++) {
      payload = parsePacket();
      if (payload) break;
      const chunk = new Uint8Array(8192);
      const n = await Promise.race([conn.read(chunk), timeout]);
      if (n === null) throw new Error("closed");
      const merged = new Uint8Array(buffer.length + n);
      merged.set(buffer);
      merged.set(chunk.subarray(0, n), buffer.length);
      buffer = merged;
      if (buffer.length > 200000) throw new Error("too large");
    }
    if (!payload) throw new Error("no packet");

    let index = 0;
    const readVarInt = (): number => {
      let value = 0, shift = 0, b = 0;
      do {
        b = payload![index++];
        value |= (b & 0x7f) << shift;
        shift += 7;
      } while (b & 0x80);
      return value;
    };
    readVarInt(); // packet id
    const jsonLength = readVarInt();
    const json = JSON.parse(new TextDecoder().decode(payload.subarray(index, index + jsonLength)));

    const favicon = typeof json.favicon === "string"
      && /^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(json.favicon)
      && json.favicon.length < 150000
      ? json.favicon : null;

    return {
      live: true,
      latency_ms: Date.now() - started,
      players_online: Number(json.players?.online ?? 0) || 0,
      players_max: Number(json.players?.max ?? 0) || 0,
      version: String(json.version?.name ?? ""),
      motd: flattenMotd(json.description),
      favicon,
    };
  } catch (e) {
    return { live: false, error: String((e as Error).message ?? e) };
  } finally {
    if (timerId !== undefined) clearTimeout(timerId);
    try { conn?.close(); } catch (_e) { /* already gone */ }
  }
}

const PING_CACHE = new Map<string, { at: number; result: any }>();

async function pingCached(hostname: string, port: number) {
  const key = `${hostname}:${port}`;
  const hit = PING_CACHE.get(key);
  if (hit && Date.now() - hit.at < (hit.result.live ? 60_000 : 20_000)) return hit.result;
  const result = await pingMinecraft(hostname, port);
  PING_CACHE.set(key, { at: Date.now(), result });
  return result;
}

/** the java tunnel port of a server: the band below 40000 (bedrock is 40000-49999, voicechat above). */
async function javaPortFor(admin: Admin, host: string): Promise<number | null> {
  const { data } = await admin.from("koda_ports").select("port").eq("host", host);
  const port = (data ?? []).map((r: any) => Number(r.port)).find((p) => p < 40000);
  return port ?? null;
}

async function serverDetail(admin: Admin, hostParam: string, url: URL, cors: Record<string, string>) {
  const host = hostParam.toLowerCase();
  const { data } = await admin
    .from("koda_servers")
    .select(SERVER_PUBLIC_COLUMNS)
    .eq("host", host)
    .maybeSingle();
  const row = data as any;
  if (!row || String(row.host).startsWith("deleted_") || row.is_banned === true) {
    return fail("not_found", 404, cors);
  }

  const owner = await ownerFor(admin, row.owner_app_uuid);
  const base: any = mapServerRow(row, owner);
  base.pvp = row.pvp ?? null;
  base.whitelist = row.whitelist ?? null;
  base.ram_mb = row.ram_mb ?? null;

  let live: any = null;
  if (url.searchParams.get("ping") !== "0") {
    const port = await javaPortFor(admin, host);
    if (port) live = await pingCached(`${host}.${base.domain}`, port);
  }

  return json({ ok: true, server: { ...base, live } }, 200, cors);
}

// ---------------------------------------------------------------------------
// guest invites: /v1/invite/{token} is what the public invite page calls. the
// token only exists in server_invites, there is no host without one. no owner
// identity is returned on purpose, a guest should not learn who hosts it.
// ---------------------------------------------------------------------------

const INVITE_TOKEN_RE = /^[a-f0-9]{18}$/;

async function inviteInfo(admin: Admin, tokenParam: string, cors: Record<string, string>) {
  const token = String(tokenParam ?? "").trim().toLowerCase();
  if (!INVITE_TOKEN_RE.test(token)) return fail("invite_invalid", 410, cors);

  // one atomic take: validity (revoked, expired, used up) and the counter
  // move together, so a one-time link cannot be used twice
  const { data, error } = await admin.rpc("fn_use_server_invite", { p_token: token });
  const inv = Array.isArray(data) ? data[0] : data;
  if (error || !inv) return fail("invite_invalid", 410, cors);

  const host = String(inv.host ?? "");
  const { data: rowData } = await admin
    .from("koda_servers")
    .select("host,base_domain,server_version,online_players,max_players,motd,is_banned,bedrock_port")
    .eq("host", host)
    .maybeSingle();
  const row = rowData as any;
  if (!row || String(row.host).startsWith("deleted_") || row.is_banned === true) {
    return fail("not_found", 404, cors);
  }

  const state = serverStatus(row);
  const domain = row.base_domain || "kodanetwork.eu";
  const java = `${host}.${domain}`;

  let live: any = null;
  const port = await javaPortFor(admin, host);
  if (port) live = await pingCached(java, port);

  const bedrockPort = Number(row.bedrock_port ?? 0) || 0;
  return json({
    ok: true,
    invite: {
      note: inv.note ?? null,
      uses: Number(inv.uses ?? 0),
      max_uses: inv.max_uses ?? null,
      expires_at: inv.expires_at ?? null,
      can_control: inv.can_control === true,
    },
    server: {
      host,
      motd: row.motd ?? "",
      status: state.status,
      version: state.version,
      players_online: Number(row.online_players ?? 0) || 0,
      players_max: Number(row.max_players ?? 0) || 0,
    },
    live,
    join: {
      java,
      bedrock: bedrockPort > 0 ? { address: java, port: bedrockPort } : null,
    },
  }, 200, cors);
}

/**
 * A signed-in account accepts an invite: a guest row appears in server_guests and
 * the server shows up in their website Servers tab (control + KodaDash access when
 * the invite granted control). An existing control grant is never downgraded.
 */
async function acceptInvite(admin: Admin, user: { id: string }, tokenParam: string, cors: Record<string, string>) {
  const token = String(tokenParam ?? "").trim().toLowerCase();
  if (!INVITE_TOKEN_RE.test(token)) return fail("invite_invalid", 410, cors);

  const { data, error } = await admin.rpc("fn_check_server_invite", { p_token: token });
  const inv = Array.isArray(data) ? data[0] : data;
  if (error || !inv) return fail("invite_invalid", 410, cors);

  const host = String(inv.host ?? "");
  const { data: rowData } = await admin
    .from("koda_servers")
    .select("host,is_banned")
    .eq("host", host)
    .maybeSingle();
  const row = rowData as any;
  if (!row || String(row.host).startsWith("deleted_") || row.is_banned === true) {
    return fail("not_found", 404, cors);
  }

  const { data: existing } = await admin
    .from("server_guests")
    .select("can_control")
    .eq("host", host)
    .eq("auth_id", user.id)
    .maybeSingle();
  const canControl = inv.can_control === true || (existing as any)?.can_control === true;

  const { error: upErr } = await admin
    .from("server_guests")
    .upsert({ host, auth_id: user.id, can_control: canControl }, { onConflict: "host,auth_id" });
  if (upErr) return fail("accept_failed", 500, cors);

  return json({ ok: true, host, can_control: canControl }, 200, cors);
}

/**
 * Dashboard action of a signed-in account: the owner of the server, or a guest
 * with a control grant, writes CMD:START/STOP/RESTART into the row. The hosting
 * device picks it up through the normal command channel.
 */
async function myServerAction(admin: Admin, user: { id: string }, hostParam: string, req: Request, cors: Record<string, string>) {
  const host = String(hostParam ?? "").toLowerCase();
  const body = await jsonBody(req);
  const action = String((body as any)?.action ?? "").toLowerCase();
  const command = action === "start" ? "CMD:START"
    : action === "stop" ? "CMD:STOP"
    : action === "restart" ? "CMD:RESTART"
    : "";
  if (!command) return fail("unknown_action", 400, cors);

  const { data: rowData } = await admin
    .from("koda_servers")
    .select("host,owner_app_uuid,is_banned")
    .eq("host", host)
    .maybeSingle();
  const row = rowData as any;
  if (!row || String(row.host).startsWith("deleted_") || row.is_banned === true) {
    return fail("not_found", 404, cors);
  }

  let allowed = false;
  const { data: guest } = await admin
    .from("server_guests")
    .select("can_control")
    .eq("host", host)
    .eq("auth_id", user.id)
    .maybeSingle();
  if ((guest as any)?.can_control === true) allowed = true;
  if (!allowed) {
    const { data: own } = await admin
      .from("koda_users")
      .select("app_uuid")
      .eq("auth_id", user.id)
      .eq("app_uuid", String(row.owner_app_uuid))
      .maybeSingle();
    allowed = !!own;
  }
  if (!allowed) return fail("not_authorized", 403, cors);

  const { error: upErr } = await admin.from("koda_servers").update({ server_version: command }).eq("host", host);
  if (upErr) return fail("command_failed", 500, cors);

  return json({ ok: true, action, host }, 200, cors);
}

// ---------------------------------------------------------------------------
// tickets (normal tickets only; PERSONAL stays end to end encrypted in the app)
// ---------------------------------------------------------------------------

async function myTickets(admin: Admin, user: { id: string }, cors: Record<string, string>) {
  const uuids = await appUuidsFor(admin, user.id);
  if (uuids.length === 0) return json({ ok: true, items: [] }, 200, cors);

  const { data: tickets } = await admin.from("support_tickets")
    .select("id,created_at,ticket_type,reference_id,title,status")
    .in("reporter_uuid", uuids)
    .neq("ticket_type", "PERSONAL")
    .order("created_at", { ascending: false })
    .limit(50);
  const rows = (tickets ?? []) as any[];
  if (rows.length === 0) return json({ ok: true, items: [] }, 200, cors);

  const { data: msgs } = await admin.from("support_ticket_messages")
    .select("ticket_id,message,created_at,is_admin")
    .in("ticket_id", rows.map((t) => t.id))
    .order("created_at", { ascending: false });
  const last = new Map<string, any>();
  for (const m of (msgs ?? []) as any[]) {
    if (!last.has(m.ticket_id)) last.set(m.ticket_id, m);
  }

  const items = rows.map((t) => ({
    ...t,
    last_message: last.has(t.id)
      ? {
        message: String(last.get(t.id).message ?? "").slice(0, 160),
        created_at: last.get(t.id).created_at,
        is_admin: !!last.get(t.id).is_admin,
      }
      : null,
  }));
  return json({ ok: true, items }, 200, cors);
}

async function ticketDetail(admin: Admin, user: { id: string }, ticketId: string, cors: Record<string, string>) {
  const uuids = await appUuidsFor(admin, user.id);
  const { data: ticket } = await admin.from("support_tickets")
    .select("id,created_at,ticket_type,reference_id,title,status,reporter_uuid")
    .eq("id", ticketId).maybeSingle();
  if (!ticket) return fail("not_found", 404, cors);
  const t = ticket as any;
  if (!uuids.includes(String(t.reporter_uuid))) return fail("not_found", 404, cors);
  if (t.ticket_type === "PERSONAL") return fail("personal_ticket_app_only", 403, cors);

  const { data: msgs } = await admin.from("support_ticket_messages")
    .select("id,created_at,sender_uuid,message,attachment_url,is_admin")
    .eq("ticket_id", ticketId)
    .order("created_at", { ascending: true });

  return json({
    ok: true,
    ticket: {
      id: t.id,
      created_at: t.created_at,
      ticket_type: t.ticket_type,
      reference_id: t.reference_id,
      title: t.title,
      status: t.status,
      messages: msgs ?? [],
    },
  }, 200, cors);
}

async function postTicketMessage(admin: Admin, user: { id: string }, ticketId: string, body: any, cors: Record<string, string>) {
  const message = str(body.message, 4000);
  if (!message) return fail("invalid_message", 400, cors);
  const retry = await rateTake(admin, `msg:${user.id}`, RATE_TICKET_MESSAGES);
  if (retry > 0) return tooMany(retry, cors);

  const uuids = await appUuidsFor(admin, user.id);
  const { data: ticket } = await admin.from("support_tickets")
    .select("id,reporter_uuid,ticket_type,status")
    .eq("id", ticketId).maybeSingle();
  if (!ticket) return fail("not_found", 404, cors);
  const t = ticket as any;
  if (!uuids.includes(String(t.reporter_uuid))) return fail("not_found", 404, cors);
  if (t.ticket_type === "PERSONAL") return fail("personal_ticket_app_only", 403, cors);
  if (["RESOLVED", "CLOSED"].includes(String(t.status))) return fail("ticket_closed", 409, cors);

  const { error } = await admin.from("support_ticket_messages")
    .insert({ ticket_id: ticketId, sender_uuid: t.reporter_uuid, message, is_admin: false });
  if (error) return fail("internal_error", 500, cors);
  return json({ ok: true }, 200, cors);
}

// ---------------------------------------------------------------------------
// admin: one-shot DNS bootstrap for the api subdomain (service role only).
// the target ip arrives in the request body on purpose, the repo stays free of
// instance data.
// ---------------------------------------------------------------------------

/** a bearer only counts as service role when this project's auth admin api accepts it. */
async function isServiceRole(token: string): Promise<boolean> {
  if (!token) return false;
  const res = await fetch(
    `${Deno.env.get("SUPABASE_URL") ?? ""}/auth/v1/admin/users?per_page=1`,
    { headers: { apikey: token, Authorization: `Bearer ${token}` } },
  ).catch(() => null);
  return !!res && res.ok;
}

async function dnsBootstrap(req: Request, body: any, cors: Record<string, string>) {
  const header = req.headers.get("authorization") ?? "";
  const bearer = header.toLowerCase().startsWith("bearer ") ? header.slice(7).trim() : "";
  const token = bearer || (req.headers.get("apikey") ?? "").trim();
  if (!(await isServiceRole(token))) {
    return fail("unauthorized", 401, cors);
  }

  const targetIp = str(body.target_ip, 64);
  const domain = (str(body.domain, 100) ?? "kodanetwork.eu").toLowerCase();
  const fqdn = (str(body.fqdn, 150) ?? `api.${domain}`).toLowerCase();
  if (!targetIp || !/^[0-9]{1,3}(\.[0-9]{1,3}){3}$/.test(targetIp)) return fail("invalid_target_ip", 400, cors);
  if (!fqdn.endsWith(`.${domain}`)) return fail("invalid_fqdn", 400, cors);

  const apiKey = `${Deno.env.get("IONOS_API_PREFIX") ?? ""}.${Deno.env.get("IONOS_API_SECRET") ?? ""}`;
  if (apiKey === ".") return fail("ionos_credentials_missing", 500, cors);

  try {
    const zonesRes = await fetch("https://api.hosting.ionos.com/dns/v1/zones", {
      headers: { "X-API-Key": apiKey },
    });
    const zones = await zonesRes.json();
    const zone = (zones as any[])?.find((z: any) => z.name === domain);
    if (!zone) throw new Error(`zone ${domain} not found`);

    // idempotent: drop an existing A record for the fqdn first
    const existingRes = await fetch(
      `https://api.hosting.ionos.com/dns/v1/zones/${zone.id}?recordName=${encodeURIComponent(fqdn)}&recordType=A`,
      { headers: { "X-API-Key": apiKey } },
    );
    if (existingRes.ok) {
      const existing = await existingRes.json();
      for (const rec of existing?.records ?? []) {
        await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zone.id}/records/${rec.id}`, {
          method: "DELETE",
          headers: { "X-API-Key": apiKey },
        });
      }
    }

    const createRes = await fetch(`https://api.hosting.ionos.com/dns/v1/zones/${zone.id}/records`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-API-Key": apiKey },
      body: JSON.stringify([{ name: fqdn, type: "A", content: targetIp, ttl: 3600, disabled: false }]),
    });
    if (!createRes.ok) throw new Error("A record creation failed: " + await createRes.text());
    const created = await createRes.json();
    return json({ ok: true, fqdn, target_ip: targetIp, record_id: created?.[0]?.id ?? null }, 200, cors);
  } catch (e) {
    return json({ ok: false, error: "dns_error", detail: String((e as Error).message) }, 500, cors);
  }
}

// ---------------------------------------------------------------------------
// router
// ---------------------------------------------------------------------------

Deno.serve(async (req) => {
  const cors = corsFor(req.headers.get("origin"));
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });

  const admin = adminClient();

  // the abuse brake in front of everything: generous read window, tighter writes
  const ipRetry = await rateTake(
    admin,
    `ip:${clientIp(req)}`,
    req.method === "GET" ? RATE_PER_IP_READ : RATE_PER_IP_WRITE,
  );
  if (ipRetry > 0) return tooMany(ipRetry, cors);

  try {
    const url = new URL(req.url);
    // the edge runtime hands over different prefixes depending on the gateway
    // ("/functions/v1/api/...", "/api/..." or already "/v1/..."), normalize all of them.
    let route = url.pathname;
    for (const prefix of ["/functions/v1/api", "/api"]) {
      if (route === prefix) {
        route = "/";
        break;
      }
      if (route.startsWith(prefix + "/")) {
        route = route.slice(prefix.length);
        break;
      }
    }
    if (route === "" || route === "/") {
      if (req.method !== "GET") return fail("method_not_allowed", 405, cors);
      const status = await buildStatus(admin);
      return new Response(statusPageHtml(status), {
        status: 200,
        headers: { ...cors, "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" },
      });
    }

    const seg = route.split("/").filter(Boolean); // ["v1", ...]
    if (seg[0] !== "v1") return fail("not_found", 404, cors);

    // --- public ---
    if (seg.length === 2 && seg[1] === "status" && req.method === "GET") {
      return json(await buildStatus(admin), 200, cors);
    }
    if (seg.length === 2 && seg[1] === "releases" && req.method === "GET") {
      const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 10) || 10, 1), 50);
      const { data } = await admin.from("releases")
        .select("version_code,version_name,channel,title,changelog,changelog_short,download_url,image_url,created_at")
        .eq("is_published", true)
        .order("version_code", { ascending: false })
        .limit(limit);
      return json({ ok: true, items: data ?? [] }, 200, cors);
    }
    if (seg.length === 2 && seg[1] === "extensions" && req.method === "GET") {
      return await listExtensions(admin, url, cors);
    }
    if (seg.length === 2 && seg[1] === "servers" && req.method === "GET") {
      return await publicServers(admin, url, cors);
    }
    if (seg.length === 3 && seg[1] === "servers" && req.method === "GET") {
      return await serverDetail(admin, seg[2], url, cors);
    }
    if (seg.length === 3 && seg[1] === "invite" && req.method === "GET") {
      return await inviteInfo(admin, seg[2], cors);
    }
    if (seg.length === 3 && seg[1] === "extensions" && req.method === "GET") {
      return await getExtension(admin, seg[2], cors);
    }
    if (seg.length === 4 && seg[1] === "extensions" && seg[3] === "download" && req.method === "GET") {
      return await downloadExtension(admin, seg[2], url, cors);
    }

    // --- JWT ---
    const jwtRoutes = seg[1] === "me" || seg[1] === "assets"
      || (seg[1] === "extensions" && req.method !== "GET")
      // accepting an invite needs an account; the token based /action route stays anonymous
      || (seg[1] === "invite" && seg[3] === "accept" && req.method === "POST");
    if (jwtRoutes) {
      const user = await requireUser(req, admin);
      if (!user) return fail("unauthorized", 401, cors);
      const userRetry = await rateTake(admin, `user:${user.id}`, RATE_PER_USER);
      if (userRetry > 0) return tooMany(userRetry, cors);

      if (seg.length === 4 && seg[1] === "invite" && seg[3] === "accept" && req.method === "POST") {
        return await acceptInvite(admin, user, seg[2], cors);
      }
      if (seg.length === 5 && seg[1] === "me" && seg[2] === "servers" && seg[4] === "action" && req.method === "POST") {
        return await myServerAction(admin, user, seg[3], req, cors);
      }

      if (seg.length === 2 && seg[1] === "assets" && req.method === "POST") {
        return await uploadAsset(admin, user, await jsonBody(req), cors);
      }

      if (seg.length === 2 && seg[1] === "me") {
        if (req.method === "GET") return await getMe(admin, user, cors);
        if (req.method === "PATCH") return await patchMe(admin, user, await jsonBody(req), cors);
      }
      if (seg.length === 3 && seg[1] === "me" && seg[2] === "avatar") {
        if (req.method === "POST") return await uploadAvatar(admin, user, await jsonBody(req), cors);
        if (req.method === "DELETE") return await deleteAvatar(admin, user, cors);
      }
      if (seg.length === 3 && seg[1] === "me" && seg[2] === "extensions" && req.method === "GET") {
        return await myExtensions(admin, user, cors);
      }
      if (seg.length === 3 && seg[1] === "me" && seg[2] === "devices" && req.method === "GET") {
        return await myDevices(admin, user, cors);
      }
      if (seg.length === 5 && seg[1] === "me" && seg[2] === "devices" && seg[4] === "unlink" && req.method === "POST") {
        return await unlinkDevice(admin, user, seg[3], cors);
      }
      if (seg.length === 3 && seg[1] === "me" && seg[2] === "tickets" && req.method === "GET") {
        return await myTickets(admin, user, cors);
      }
      if (seg.length === 4 && seg[1] === "me" && seg[2] === "tickets" && req.method === "GET") {
        return await ticketDetail(admin, user, seg[3], cors);
      }
      if (seg.length === 5 && seg[1] === "me" && seg[2] === "tickets" && seg[4] === "messages" && req.method === "POST") {
        return await postTicketMessage(admin, user, seg[3], await jsonBody(req), cors);
      }

      if (seg.length === 2 && seg[1] === "extensions" && req.method === "POST") {
        return await submitExtension(admin, user, await jsonBody(req), cors);
      }
      if (seg.length === 4 && seg[1] === "extensions" && seg[3] === "versions" && req.method === "POST") {
        return await addVersion(admin, user, seg[2], await jsonBody(req), cors);
      }
      if (seg.length === 4 && seg[1] === "extensions" && seg[3] === "finalize" && req.method === "POST") {
        return await finalizeVersion(admin, user, seg[2], await jsonBody(req), cors);
      }
      if (seg.length === 4 && seg[1] === "extensions" && seg[3] === "withdraw" && req.method === "POST") {
        return await withdrawExtension(admin, user, seg[2], cors);
      }
    }

    // --- service role ---
    if (seg.length === 3 && seg[1] === "admin" && seg[2] === "dns-bootstrap" && req.method === "POST") {
      return await dnsBootstrap(req, await jsonBody(req), cors);
    }

    return fail("not_found", 404, cors);
  } catch (e) {
    console.error("api error:", e);
    return fail("internal_error", 500, cors);
  }
});
