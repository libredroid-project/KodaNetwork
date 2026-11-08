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
// delete-account: the account, and only the account. the device keeps its servers
// and files, the login is removed. the full data delete is a separate, approved
// request (see the data-request function).
//
// POST { app_uuid, device_token } -> { ok: true }
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.6";
import { corsHeaders } from "../_shared/cors.ts";
import { adminClient, jsonError, verifyDeviceToken } from "../_shared/deviceAuth.ts";
import { sendKodaMail } from "../_shared/resend.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  try {
    const body = await req.json().catch(() => ({}));
    const appUuid = String(body.app_uuid ?? "").trim();
    const deviceToken = String(body.device_token ?? "").trim();
    const admin = adminClient();

    if (!(await verifyDeviceToken(admin, appUuid, deviceToken))) {
      return jsonError("unauthorized", 401);
    }

    const { data: rows } = await admin
      .from("koda_users")
      .select("auth_id, mc_username")
      .eq("app_uuid", appUuid);
    const authId = (rows ?? []).find((r: any) => r.auth_id)?.auth_id ?? null;
    const mcName = (rows ?? []).find((r: any) => r.mc_username)?.mc_username ?? null;

    let mail: string | null = null;
    if (authId) {
      const { data: userData } = await admin.auth.admin.getUserById(authId);
      mail = userData?.user?.email ?? null;
    }

    // unlink first: even if the auth delete below fails, the device is clean
    const { error: unlinkError } = await admin.rpc("rpc_delete_my_account", {
      p_app_uuid: appUuid,
      p_device_token: deviceToken,
    });
    if (unlinkError) return jsonError("unlink_failed", 500);

    if (authId) {
      const { error: deleteError } = await admin.auth.admin.deleteUser(authId);
      if (deleteError) {
        // the device is already unlinked, so a retry is safe and the mail is not sent
        return new Response(JSON.stringify({ ok: false, error: "auth_delete_failed" }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    if (mail) {
      await sendKodaMail({
        to: mail,
        subject: "Your KodaHosting account was deleted",
        heading: "Account deleted",
        paragraphs: [
          mcName
            ? `The account for ${mcName} was removed from this device.`
            : "Your KodaHosting account was removed from this device.",
          "Your servers and files stay on the phone and keep running. If you want all data deleted as well, use \"Request full data deletion\" in the app.",
        ],
        footnote: "If this was not you, contact contact@kodaserv.eu immediately.",
      });
    }

    return new Response(JSON.stringify({ ok: true, had_account: !!authId }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e) {
    console.error("delete-account error:", e);
    return jsonError("internal_error", 500);
  }
});
