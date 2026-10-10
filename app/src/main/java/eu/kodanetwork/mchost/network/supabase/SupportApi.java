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
package eu.kodanetwork.mchost.network.supabase;

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
        // the support RPCs authorize with the device token inside the request body. a user
        // session token can be expired, which made every ticket call fail with 401 before
        // the RPC even ran, so the anon key goes here, like everywhere else.
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
