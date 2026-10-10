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

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

/**
 * renders minecraft colour codes for the in-app MOTD builder.
 *
 * understands every code the game does:
 * - colours &0 .. &f (the section sign § works too)
 * - formats &k obfuscated, &l bold, &o italic, &n underline, &m strike, &r reset
 * - hex colours as &#RRGGBB, &lt;#RRGGBB&gt; (MiniMessage style) and the wire
 *   form the server stores (&x&r&r&g&g&b&b)
 *
 * the codes get eaten, not shown, and the visible text carries the spans, so the
 * preview matches what the server list will render.
 */
public final class MinecraftColors {

    private static final String CODES = "0123456789abcdef";
    private static final int[] COLORS = {
            Color.parseColor("#000000"), Color.parseColor("#0000AA"), Color.parseColor("#00AA00"),
            Color.parseColor("#00AAAA"), Color.parseColor("#AA0000"), Color.parseColor("#AA00AA"),
            Color.parseColor("#FFAA00"), Color.parseColor("#AAAAAA"), Color.parseColor("#555555"),
            Color.parseColor("#5555FF"), Color.parseColor("#55FF55"), Color.parseColor("#55FFFF"),
            Color.parseColor("#FF5555"), Color.parseColor("#FF55FF"), Color.parseColor("#FFFF55"),
            Color.parseColor("#FFFFFF")
    };

    private MinecraftColors() {
    }

    /** the green (#55FF55) the preview starts with. */
    public static int defaultColor() {
        return COLORS[10];
    }

    /** colour of one code letter, -1 when that letter is no colour. */
    /**
     * turns what the user typed into what a server actually reads.
     *
     * the field works with ampersand codes because that is what people know, but vanilla
     * and paper only understand the section sign. hex colours become the §x§r§r§g§g§b§b
     * wire form on 1.16 and newer, older servers get the closest of the sixteen colours.
     */
    public static String toServerMotd(String input, boolean supportsHex) {
        if (input == null) return "";
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '&' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);

                // hex colour, both spellings people use
                if (next == '#' && i + 8 <= input.length() && isHex(input.substring(i + 2, i + 8))) {
                    out.append(hexToServer(input.substring(i + 2, i + 8), supportsHex));
                    i += 7;
                    continue;
                }
                if (next == '<' && i + 9 < input.length() && input.charAt(i + 8) == '>'
                        && isHex(input.substring(i + 2, i + 8))) {
                    out.append(hexToServer(input.substring(i + 2, i + 8), supportsHex));
                    i += 8;
                    continue;
                }
                if (CODES.indexOf(Character.toLowerCase(next)) >= 0
                        || "klmnor".indexOf(Character.toLowerCase(next)) >= 0) {
                    out.append('\u00A7').append(Character.toLowerCase(next));
                    i++;
                    continue;
                }
            }

            // an existing section sign stays, that value is already in server form
            out.append(c);
        }
        return toPropertiesEscaped(out.toString());
    }

    /**
     * escapes a value the way a java properties file needs it.
     *
     * minecraft reads server.properties as ISO-8859-1, so a plain section sign (or any
     * other non ascii letter, a chinese motd for example) turns into mojibake. vanilla
     * therefore writes \u00A7, and this does the same.
     */
    public static String toPropertiesEscaped(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c < 0x20 || c > 0x7E) out.append(String.format("\\u%04X", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    /** the other way round: a value read from the file back into plain text. */
    public static String fromPropertiesEscaped(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'u' && i + 5 < value.length()) {
                    try {
                        out.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (Exception ignored) {}
                }
                if (next == '\\') { out.append('\\'); i++; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** the server form of one hex colour: the wire on 1.16+, the closest colour before that. */
    private static String hexToServer(String rgb, boolean supportsHex) {
        if (!supportsHex) {
            int r = Integer.parseInt(rgb.substring(0, 2), 16);
            int g = Integer.parseInt(rgb.substring(2, 4), 16);
            int b = Integer.parseInt(rgb.substring(4, 6), 16);
            return "\u00A7" + nearestLegacy(r, g, b);
        }
        StringBuilder out = new StringBuilder("\u00A7x");
        for (int i = 0; i < 6; i++) out.append('\u00A7').append(Character.toLowerCase(rgb.charAt(i)));
        return out.toString();
    }

    /** the closest of the sixteen colours, for servers that cannot do hex. */
    private static char nearestLegacy(int r, int g, int b) {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < 16; i++) {
            int cr = (COLORS[i] >> 16) & 0xFF, cg = (COLORS[i] >> 8) & 0xFF, cb = COLORS[i] & 0xFF;
            long dist = (long) (r - cr) * (r - cr) + (long) (g - cg) * (g - cg) + (long) (b - cb) * (b - cb);
            if (dist < bestDist) { bestDist = dist; best = i; }
        }
        return CODES.charAt(best);
    }

    /**
     * the other direction, for the editor field: what stands in the file becomes the
     * ampersand form the field works with, escapes included.
     */
    public static String toAmpersand(String serverValue) {
        if (serverValue == null) return "";
        return fromPropertiesEscaped(serverValue).replace('\u00A7', '&');
    }

    public static int colorFor(char code) {
        int index = CODES.indexOf(Character.toLowerCase(code));
        return index < 0 ? -1 : COLORS[index];
    }

    /** the styles collected for the run being built right now. */
    private static class Style {
        int color = defaultColor();
        boolean bold, italic, underline, strike, obfuscated;
    }

    /** closes the current run, over everything appended since {@code from}. */
    private static void flush(SpannableStringBuilder out, int from, Style st) {
        int end = out.length();
        if (end <= from) return;
        out.setSpan(new ForegroundColorSpan(st.color), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (st.bold) out.setSpan(new StyleSpan(Typeface.BOLD), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (st.italic) out.setSpan(new StyleSpan(Typeface.ITALIC), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (st.underline) out.setSpan(new UnderlineSpan(), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (st.strike) out.setSpan(new StrikethroughSpan(), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (st.obfuscated) {
            // android cannot scramble text, monospace at least hints at it
            out.setSpan(new android.text.style.TypefaceSpan("monospace"), from, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** a hex hit: the colour and how many chars it swallowed. */
    private static class HexMatch {
        final int color;
        final int len;

        HexMatch(int color, int len) {
            this.color = color;
            this.len = len;
        }
    }

    /** reads "&#RRGGBB" (8 chars), "<#RRGGBB>" (9) or "&x&r&r&g&g&b&b" (14) at i. */
    private static HexMatch parseHex(String s, int i) {
        try {
            if (i + 8 <= s.length() && s.charAt(i) == '&' && s.charAt(i + 1) == '#'
                    && isHex(s.substring(i + 2, i + 8))) {
                return new HexMatch(Color.parseColor("#" + s.substring(i + 2, i + 8)), 8);
            }
            if (i + 9 <= s.length() && s.charAt(i) == '<' && s.charAt(i + 1) == '#'
                    && s.charAt(i + 8) == '>' && isHex(s.substring(i + 2, i + 8))) {
                return new HexMatch(Color.parseColor("#" + s.substring(i + 2, i + 8)), 9);
            }
            // wire form: &x and then six single hex digits, each behind its own &
            if (i + 14 <= s.length() && s.charAt(i) == '&' && Character.toLowerCase(s.charAt(i + 1)) == 'x') {
                StringBuilder hex = new StringBuilder("#");
                for (int k = 0; k < 6; k++) {
                    int at = i + 2 + k * 2;
                    if (s.charAt(at) != '&') return null;
                    char c = s.charAt(at + 1);
                    if (!isHex(String.valueOf(c))) return null;
                    hex.append(c);
                }
                return new HexMatch(Color.parseColor(hex.toString()), 14);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return !s.isEmpty();
    }

    /**
     * turns colour codes into styled text. unknown sequences stay visible, that
     * way a typo does not vanish silently.
     */
    public static CharSequence toSpannable(String text) {
        String s = text == null ? "" : text.replace('§', '&');
        SpannableStringBuilder out = new SpannableStringBuilder();
        Style st = new Style();
        int runFrom = 0;
        int i = 0;
        while (i < s.length()) {
            HexMatch hex = parseHex(s, i);
            if (hex != null) {
                flush(out, runFrom, st);
                st = new Style();
                st.color = hex.color;
                runFrom = out.length();
                i += hex.len;
                continue;
            }
            if (s.charAt(i) == '&' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                int color = colorFor(code);
                if (color != -1) {
                    flush(out, runFrom, st);
                    st = new Style();
                    st.color = color;
                    runFrom = out.length();
                    i += 2;
                    continue;
                }
                boolean known = true;
                switch (code) {
                    case 'l': flush(out, runFrom, st); st.bold = true; runFrom = out.length(); break;
                    case 'o': flush(out, runFrom, st); st.italic = true; runFrom = out.length(); break;
                    case 'n': flush(out, runFrom, st); st.underline = true; runFrom = out.length(); break;
                    case 'm': flush(out, runFrom, st); st.strike = true; runFrom = out.length(); break;
                    case 'k': flush(out, runFrom, st); st.obfuscated = true; runFrom = out.length(); break;
                    case 'r': flush(out, runFrom, st); st = new Style(); runFrom = out.length(); break;
                    default: known = false;
                }
                if (known) {
                    i += 2;
                    continue;
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        flush(out, runFrom, st);
        return out;
    }
}
