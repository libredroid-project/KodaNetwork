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
package eu.kodanetwork.lobby.security;

import java.util.Base64;

public class PraetorSecurity {
    
    // supabase url and key, reversed and base64'd so a jar grab does not hand them out
    private static final String OBF_URL = "==wbj5SZzFmYhBXdz5ya4JGbiBXY1lHctJncmBnelN3Yz9yL6MHc0RHa";
    private static final String OBF_KEY = "==AOUplYOxENR1yNtQ3bsVVSIlEOZt0c6llevhnRhVEeuFESYBXUrZzbyhkcuAjbOl3Z65EeVpWT1EkaNZTSDNGNW1WSzllaNRTRE5UNZpnTzUkaPlWUYlFcKNETpRjMiVnRtlkNJNlWzlTbjl2dplkco5WWzp0RjhmVYV2dx02Y5p1RjZjVyMmaO5WS2kUaaxmSul0cJNlW6ZUbZhmQYRmeKl2Tp10MjBnS5VmL5o0QWhFcrlkNJN0Y1IlbJNXSp5UMJpXVJpUaPl2YHJGaKlXZ";

    private static String decode(String obf) {
        String reversed = new StringBuilder(obf).reverse().toString();
        return new String(Base64.getDecoder().decode(reversed), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String getSupabaseUrl() {
        return decode(OBF_URL);
    }

    public static String getSupabaseKey() {
        return decode(OBF_KEY);
    }
}
