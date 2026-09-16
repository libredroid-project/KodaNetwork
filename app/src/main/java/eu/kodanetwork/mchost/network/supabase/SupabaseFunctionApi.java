package eu.kodanetwork.mchost.network.supabase;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under:
 *   - GNU General Public License v3 (GPL-3.0) - see LICENSE
 *   - Libre Open Project License v1.0 PREVIEW - see LOPL_v1.0_PREVIEW.md
 *   - Commercial License - see COMMERCIAL-LICENSE.md
 *
 * For commercial inquiries: licence@kodaserv.eu
 */

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface SupabaseFunctionApi {
    @POST("functions/v1/{name}")
    Call<Map<String, Object>> callFunction(
        @Path("name") String functionName,
        @Header("apikey") String anonKey,
        @Header("Authorization") String bearer,
        @Body Map<String, Object> body
    );
}
