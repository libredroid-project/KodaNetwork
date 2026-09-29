package eu.kodanetwork.mchost.util;

/**
 * Detects crisis phrases in free text a user types (server name, MOTD), so the app can show
 * support resources instead of silently carrying the words into a public server list.
 *
 * Purely local: no logging, no network, no persistence. Returns only a boolean.
 *
 * Matching rules:
 *   - long phrases (suicide, selbstmord, killmyself, ...): contained in the normalized text
 *     (lowercase, symbols stripped). They are specific enough that contains() is safe.
 *   - short slang (kms, kys): only when the whole normalized text or a single token equals it
 *     or ends with it ("iwannakms"), so everyday words like "skyscraper" stay untouched.
 *   - Chinese phrases: contained in the raw lowercased text (CJK survives normalization).
 */
public final class CrisisTextDetector {

    private CrisisTextDetector() {
    }

    /** Short gaming slang: match whole-token or token-ending only. */
    private static final String[] SHORT_PHRASES = {"kms", "kys"};

    /** Longer, unambiguous phrases: contained match on the normalized text. */
    private static final String[] LONG_PHRASES = {
            "suicide", "suicidal", "suizid", "selbstmord", "selbstverletz", "selfharm",
            "killmyself", "killingmyself", "wanttodie", "wanadie", "wannadie", "iwannadie",
            "wantokill", "endmylife", "enditall", "noreasontolive", "notworthliving",
            "dontwanttolive", "donotwanttolive", "cantgoon", "ichwillnichtmehr",
            "willnichtmehrleben", "ichwillsterben", "willsterben", "michumbringen",
            "sichumbringen", "livingispointless", "whyamilive", "wanttobegone"
    };

    /** Phrases that must START a token, so words like "skillme" stay untouched. */
    private static final String[] STARTS_PHRASES = {"killme"};

    /** Chinese phrases: contained in the raw lowercased text. */
    private static final String[] CJK_PHRASES = {"自杀", "自残"};

    /** @return true when the text contains a crisis phrase. */
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

    /** Lowercase, keeps letters/digits/CJK, drops everything else. */
    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
