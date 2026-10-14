package fr.velafly;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lecture et affichage des durées : 30m, 1h30m, 2d, 45s, 1w... (un nombre seul = minutes). */
final class TimeUtil {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)([smhdjw])");
    private static final long MAX = 315_360_000L; // 10 ans

    private TimeUtil() {
    }

    /** Retourne la durée en secondes, ou -1 si le texte est invalide. */
    static long parse(String text) {
        if (text == null) {
            return -1;
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return -1;
        }
        if (s.matches("\\d{1,7}")) {
            long v = Long.parseLong(s) * 60;
            return v > 0 && v <= MAX ? v : -1;
        }
        Matcher m = TOKEN.matcher(s);
        int pos = 0;
        long total = 0;
        while (m.find()) {
            if (m.start() != pos) {
                return -1;
            }
            long n = Long.parseLong(m.group(1).length() > 9 ? "0" : m.group(1));
            long unit = switch (m.group(2).charAt(0)) {
                case 's' -> 1L;
                case 'm' -> 60L;
                case 'h' -> 3600L;
                case 'd', 'j' -> 86400L;
                default -> 604800L;
            };
            total += n * unit;
            pos = m.end();
            if (total > MAX) {
                return -1;
            }
        }
        return pos == s.length() && total > 0 ? total : -1;
    }

    static String format(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("j ");
        }
        if (h > 0) {
            sb.append(h).append("h ");
        }
        if (m > 0) {
            sb.append(m).append("min ");
        }
        if (s > 0 || sb.length() == 0) {
            sb.append(s).append("s");
        }
        return sb.toString().trim();
    }
}
