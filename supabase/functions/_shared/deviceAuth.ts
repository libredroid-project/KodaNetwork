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
// Copyright (c) 2026 KodaHosting
// device auth for the edge functions (device bearer token model, same as the
// DB hardening from 2026-09-12). the app gets the token on start through
// rpc_get_is_banned and it is never readable from outside.
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
  // same semantics as fn_verify_device_token in the DB: there IS a row with
  // app_uuid+token. maybeSingle() would have crashed with PGRST116 on duplicate
  // rows (multi-account) and always answered "unauthorized".
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

/** the host belongs to the device (server row exists, owner fits) or the device is admin */
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

/** port quota per device (sum over all servers of that device) */
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
