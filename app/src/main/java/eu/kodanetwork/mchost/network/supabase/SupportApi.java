package eu.kodanetwork.mchost.network.supabase;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) - see LICENSE
 */

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

import eu.kodanetwork.mchost.security.PraetorSecurity;

public class SupportApi {

    public static String makeSupabaseRequest(String endpoint, String method, String jsonBody, String sessionToken) throws Exception {
        URL url = new URL(PraetorSecurity.getSupabaseUrl() + "/" + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("apikey", PraetorSecurity.getSupabaseKey());
        // The support RPCs authorize with the device token inside the request body. A user
        // session token can be expired, which made every ticket call fail with 401 before
        // the RPC even ran - so the anon key is used here, like everywhere else.
        conn.setRequestProperty("Authorization", "Bearer " + PraetorSecurity.getSupabaseKey());
        conn.setRequestProperty("Content-Type", "application/json");

        if (jsonBody != null && (method.equals("POST") || method.equals("PATCH"))) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes("utf-8");
                os.write(input, 0, input.length);
            }
        }

        int code = conn.getResponseCode();
        try (Scanner scanner = new Scanner(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8")) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }
}
