package eu.kodanetwork.mchost.ui;

import android.content.Context;

import eu.kodanetwork.mchost.network.supabase.SupportApi;

/** Small bridge so UI code can call the support API without leaking the session token. */
final class SupportApiHelper {
    private SupportApiHelper() {
    }

    static String call(Context context, String endpoint, String jsonBody) throws Exception {
        return SupportApi.makeSupabaseRequest(endpoint, "POST", jsonBody, null);
    }
}
