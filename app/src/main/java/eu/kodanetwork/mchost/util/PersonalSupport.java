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
package eu.kodanetwork.mchost.util;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import eu.kodanetwork.mchost.network.supabase.SupportApi;
import eu.kodanetwork.mchost.ui.PersonalChatActivity;

/**
 * opens the private mental-support chat with libredroid.
 *
 * the rule: at most ONE open personal conversation. an existing one is reopened,
 * otherwise a new ticket is created without asking. the caller follows the progress
 * through the callbacks, so the button can show "opening...".
 */
public final class PersonalSupport {

    private static final String TAG = "PersonalSupport";

    private PersonalSupport() {
    }

    public interface OpenCallback {
        /** busy hint while the ticket is looked up or created. */
        default void onBusy(boolean busy) {}

        /** the chat is open, or creation failed and the caller shows its own message. */
        default void onOpened() {}
    }

    /** is there an open personal conversation. the check runs off the UI thread. */
    public static void hasOpenTicket(final Context context, final java.util.function.Consumer<Boolean> callback) {
        if (context == null) {
            callback.accept(false);
            return;
        }
        new Thread(() -> {
            boolean found = false;
            try {
                String uuid = eu.kodanetwork.mchost.App.getPrefs(context).getString("app_uuid", "");
                String token = eu.kodanetwork.mchost.App.getPrefs(context).getString("device_token", "");
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("p_reporter_uuid", uuid);
                body.put("p_device_token", token);
                body.put("p_all", false);
                String response = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_tickets", "POST", body.toString(), null);
                org.json.JSONArray tickets = new org.json.JSONArray(response);
                for (int i = 0; i < tickets.length(); i++) {
                    org.json.JSONObject ticket = tickets.getJSONObject(i);
                    if ("PERSONAL".equals(ticket.optString("ticket_type", ""))
                            && "OPEN".equals(ticket.optString("status", ""))) {
                        found = true;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            final boolean result = found;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.accept(result));
        }, "KodaPersonalCheck").start();
    }

    /** finds the open personal ticket or makes one, then opens the chat. */
    public static void open(final Context context, final OpenCallback callback) {
        if (context == null) return;
        if (callback != null) callback.onBusy(true);
        new Thread(() -> {
            String existingId = null;
            String createdId = null;
            try {
                String uuid = eu.kodanetwork.mchost.App.getPrefs(context).getString("app_uuid", "");
                String token = eu.kodanetwork.mchost.App.getPrefs(context).getString("device_token", "");
                JSONObject body = new JSONObject();
                body.put("p_reporter_uuid", uuid);
                body.put("p_device_token", token);
                body.put("p_all", false);
                String response = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_tickets", "POST", body.toString(), null);
                JSONArray tickets = new JSONArray(response);
                for (int i = 0; i < tickets.length(); i++) {
                    JSONObject ticket = tickets.getJSONObject(i);
                    if ("PERSONAL".equals(ticket.optString("ticket_type", ""))
                            && "OPEN".equals(ticket.optString("status", ""))) {
                        existingId = ticket.optString("id", null);
                        break;
                    }
                }
                if (existingId == null) {
                    JSONObject create = new JSONObject();
                    create.put("p_reporter_uuid", uuid);
                    create.put("p_device_token", token);
                    create.put("p_ticket_type", "PERSONAL");
                    create.put("p_reference_id", "");
                    create.put("p_title", context.getString(eu.kodanetwork.mchost.R.string.personal_chat_ticket_title));
                    createdId = SupportApi.makeSupabaseRequest(
                            "rest/v1/rpc/rpc_create_ticket", "POST", create.toString(), null)
                            .replace("\"", "").trim();
                }
            } catch (Exception e) {
                Log.w(TAG, "open failed: " + e.getMessage());
            }
            final String ticketId = existingId != null ? existingId : createdId;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.onBusy(false);
                if (ticketId != null && !ticketId.isEmpty() && !ticketId.startsWith("{")) {
                    Intent chat = new Intent(context, PersonalChatActivity.class);
                    chat.putExtra("TICKET_ID", ticketId);
                    context.startActivity(chat);
                    if (callback != null) callback.onOpened();
                }
            });
        }, "KodaPersonalSupport").start();
    }
}
