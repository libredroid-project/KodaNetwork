package eu.kodanetwork.mchost.util;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import eu.kodanetwork.mchost.network.supabase.SupportApi;
import eu.kodanetwork.mchost.ui.PersonalChatActivity;

/**
 * Opens the private mental-support chat with libredroid.
 *
 * Rule: there is at most ONE open personal conversation. If one exists it is reopened,
 * otherwise a new ticket is created silently. The caller gets progress feedback through
 * the state callbacks so the button can show "opening...".
 */
public final class PersonalSupport {

    private static final String TAG = "PersonalSupport";

    private PersonalSupport() {
    }

    public interface OpenCallback {
        /** UI hint while the ticket is looked up / created. */
        default void onBusy(boolean busy) {}

        /** Chat opened (or creation failed and the caller shows its own message). */
        default void onOpened() {}
    }

    /** Reports whether an open personal conversation exists (runs the check off the UI thread). */
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

    /** Finds the open personal ticket, or creates one, then opens the chat. */
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
