# Copyright (c) 2026 KodaHosting
# Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License
# (see LICENSE, LOPL_v1.0_PREVIEW.md, COMMERCIAL-LICENSE.md)
// Geraete-Authentifizierung fuer Edge Functions (Device-Bearer-Token-Modell,
// identisch zur DB-Absicherung vom 2026-09-12). Der Token wird der App beim
// Start ueber rpc_get_is_banned uebergeben und nie oeffentlich lesbar.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { corsHeaders } from "./cors.ts";

export function adminClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
  );
}

export function jsonError(message: string, status: number) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export async function verifyDeviceToken(
  supabaseAdmin: ReturnType<typeof createClient>,
  appUuid: string | undefined | null,
  deviceToken: string | undefined | null
): Promise<boolean> {
  if (!appUuid || !deviceToken) return false;
  // Semantik wie fn_verify_device_token in der DB: ES GIBT eine Zeile mit
  // app_uuid+token. maybeSingle() waere bei Dubletten-Zeilen (Multi-Account)
  // mit PGRST116 abgestuerzt und haette immer "unauthorized" geliefert.
  const { data } = await supabaseAdmin
    .from("koda_users")
    .select("device_token")
    .eq("app_uuid", appUuid)
    .eq("device_token", deviceToken)
    .limit(1);
  return Array.isArray(data) && data.length > 0;
}

export async function isPraetorAdmin(
  supabaseAdmin: ReturnType<typeof createClient>,
  appUuid: string | undefined | null,
  deviceToken: string | undefined | null
): Promise<boolean> {
  if (!(await verifyDeviceToken(supabaseAdmin, appUuid, deviceToken))) return false;
  const { data } = await supabaseAdmin
    .from("praetor_admins")
    .select("app_uuid")
    .eq("app_uuid", appUuid)
    .maybeSingle();
  return !!data;
}

/** Host gehoert dem Geraet (Server-Zeile existiert und owner passt) oder Geraet ist Admin. */
export async function canManageHost(
  supabaseAdmin: ReturnType<typeof createClient>,
  appUuid: string | undefined | null,
  deviceToken: string | undefined | null,
  host: string
): Promise<boolean> {
  if (!appUuid || !deviceToken) return false;
  if (await isPraetorAdmin(supabaseAdmin, appUuid, deviceToken)) return true;
  if (!(await verifyDeviceToken(supabaseAdmin, appUuid, deviceToken))) return false;
  const { data } = await supabaseAdmin
    .from("koda_servers")
    .select("owner_app_uuid")
    .eq("host", host)
    .maybeSingle();
  return !!data && data.owner_app_uuid === appUuid;
}

/** Ports-Quota pro Geraet (Summe ueber alle Server des Geraets). */
export async function countPortsForDevice(
  supabaseAdmin: ReturnType<typeof createClient>,
  appUuid: string
): Promise<number> {
  const { data: servers } = await supabaseAdmin
    .from("koda_servers")
    .select("host")
    .eq("owner_app_uuid", appUuid);
  if (!servers || servers.length === 0) return 0;
  const hosts = servers.map((s: { host: string }) => s.host);
  const { count } = await supabaseAdmin
    .from("koda_ports")
    .select("port", { count: "exact", head: true })
    .in("host", hosts);
  return count ?? 0;
}

export const MAX_PORTS_PER_DEVICE = 30;
