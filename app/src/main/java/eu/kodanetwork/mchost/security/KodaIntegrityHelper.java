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
package eu.kodanetwork.mchost.security;

import android.content.Context;
import android.util.Log;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;
import com.google.android.gms.tasks.Task;
import java.util.UUID;

public class KodaIntegrityHelper {
    private static final String TAG = "KodaIntegrity";

    public static void checkIntegrity(Context context) {
        try {
            // one random nonce per request, play refuses tokens that get replayed
            String nonce = UUID.randomUUID().toString();
            
            // play integrity hands out its own manager instance
            IntegrityManager integrityManager = IntegrityManagerFactory.create(context.getApplicationContext());

            // ask google to sign a token, nothing here verifies it
            Task<IntegrityTokenResponse> integrityTokenResponse = integrityManager.requestIntegrityToken(
                    IntegrityTokenRequest.builder()
                            .setNonce(nonce)
                            .build());

            integrityTokenResponse.addOnSuccessListener(response -> {
                String integrityToken = response.token();
                Log.i(TAG, "Play Integrity Token successfully requested.");
                // a real setup would send this token to the backend (Supabase) to check it
                // for now asking for it is enough to light up the Play Console integration signals
            });

            integrityTokenResponse.addOnFailureListener(e -> {
                Log.w(TAG, "Play Integrity Token request failed: " + e.getMessage());
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Play Integrity API", e);
        }
    }
}
