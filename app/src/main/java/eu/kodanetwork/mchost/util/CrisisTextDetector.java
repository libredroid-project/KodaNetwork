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

/**
 * looks for crisis phrases in free text the user types (server name, MOTD), so the app can
 * show support resources instead of quietly pushing the words into a public server list.
 *
 * purely local: no logging, no network, no persistence, only a boolean comes back.
 *
 * the matching rules:
 *   - long phrases (suicide, selbstmord, killmyself, ...): contained in the normalized text
 *     (lowercase, symbols stripped), they are specific enough for contains() to be safe.
 *   - short slang (kms, kys): only when the whole normalized text or a single token equals
 *     or ends with it ("iwannakms"), so everyday words like "skyscraper" stay untouched.
 *   - Chinese phrases: contained in the raw lowercased text, CJK survives normalization.
 */
public final class CrisisTextDetector {

    private CrisisTextDetector() {
    }

    /** short slang, only a whole token or a token ending matches. */
    private static final String[] SHORT_PHRASES = {"kms", "kys"};

    /** long, unambiguous phrases, contained match on the normalized text. */
    private static final String[] LONG_PHRASES = {
            "suicide", "suicidal", "suizid", "selbstmord", "selbstverletz", "selfharm",
            "killmyself", "killingmyself", "wanttodie", "wanadie", "wannadie", "iwannadie",
            "wantokill", "endmylife", "enditall", "noreasontolive", "notworthliving",
            "dontwanttolive", "donotwanttolive", "cantgoon", "ichwillnichtmehr",
            "willnichtmehrleben", "ichwillsterben", "willsterben", "michumbringen",
            "sichumbringen", "livingispointless", "whyamilive", "wanttobegone"
    };

    /** these have to START a token, so words like "skillme" stay untouched. */
    private static final String[] STARTS_PHRASES = {"killme"};

    /** chinese phrases, contained in the raw lowercased text. */
    private static final String[] CJK_PHRASES = {"自杀", "自残"};

    /** @return true when one of the phrases shows up in the text. */
    public static boolean matches(String input) {
        if (input == null) return false;
        String raw = input.toLowerCase();
        for (String phrase : CJK_PHRASES) {
            if (raw.contains(phrase)) return true;
        }

        String normalized = normalize(raw);
        if (normalized.isEmpty()) return false;
        for (String phrase : LONG_PHRASES) {
            if (normalized.contains(phrase)) return true;
        }
        for (String phrase : SHORT_PHRASES) {
            if (normalized.equals(phrase) || normalized.endsWith(phrase)) return true;
            for (String token : normalized.split("[^a-z0-9\\u4e00-\\u9fff]+")) {
                if (token.equals(phrase) || token.endsWith(phrase)) return true;
            }
        }
        for (String phrase : STARTS_PHRASES) {
            if (normalized.startsWith(phrase)) return true;
            for (String token : normalized.split("[^a-z0-9\\u4e00-\\u9fff]+")) {
                if (token.startsWith(phrase)) return true;
            }
        }
        return false;
    }

    /** lowercase, keeps letters, digits and CJK, drops the rest. */
    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
