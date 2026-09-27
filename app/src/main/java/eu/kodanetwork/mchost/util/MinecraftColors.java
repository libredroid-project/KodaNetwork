package eu.kodanetwork.mchost.util;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.StrikethroughSpan;

/**
 * Renders Minecraft colour codes for the in-app MOTD builder.
 *
 * Accepts both the ampersand form used in the editor ({@code &a}) and the section sign form
 * ({@code §a}) the server stores, plus the format codes l, o, n, m and r.
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

    /** Green (#55FF55) used as the default editor colour for the preview. */
    public static int defaultColor() {
        return COLORS[10];
    }

    /** The colour of one code letter, or -1 when the letter is not a colour. */
    public static int colorFor(char code) {
        int index = CODES.indexOf(Character.toLowerCase(code));
        return index < 0 ? -1 : COLORS[index];
    }

    /**
     * Turns colour codes into a styled text. Unknown codes stay visible so the user notices typos.
     */
    public static CharSequence toSpannable(String text) {
        String source = text == null ? "" : text.replace('§', '&');
        SpannableString out = new SpannableString(source);
        int currentColor = defaultColor();
        boolean colorSet = true;
        int i = 0;
        while (i < source.length() - 1) {
            if (source.charAt(i) == '&') {
                char code = Character.toLowerCase(source.charAt(i + 1));
                int color = colorFor(code);
                boolean handled = true;
                if (color != -1) {
                    currentColor = color;
                    colorSet = true;
                } else if (code == 'l') {
                    out.setSpan(new StyleSpan(Typeface.BOLD), i, i + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (code == 'o') {
                    out.setSpan(new StyleSpan(Typeface.ITALIC), i, i + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (code == 'n') {
                    out.setSpan(new UnderlineSpan(), i, i + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (code == 'm') {
                    out.setSpan(new StrikethroughSpan(), i, i + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (code == 'r') {
                    currentColor = defaultColor();
                } else {
                    handled = false;
                }
                if (handled) {
                    out.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), i, i + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i += 2;
                    continue;
                }
            }
            i++;
        }
        // Colour runs: from each colour code to the next one
        if (colorSet) {
            int start = 0;
            int active = defaultColor();
            i = 0;
            while (i < source.length() - 1) {
                if (source.charAt(i) == '&') {
                    int color = colorFor(Character.toLowerCase(source.charAt(i + 1)));
                    if (color != -1) {
                        if (i > start) {
                            out.setSpan(new ForegroundColorSpan(active), start, i, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        active = color;
                        start = i + 2;
                        i += 2;
                        continue;
                    }
                }
                i++;
            }
            if (source.length() > start) {
                out.setSpan(new ForegroundColorSpan(active), start, source.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }
}
