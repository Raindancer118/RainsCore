package de.raindancer.core.platform.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Did you mean": the names close to one somebody mistyped — a world, a setting, a warp, a kit.
 *
 * <p>Close is a couple of letters off (a third of the length for longer names; two letters swapped
 * count as one slip), or containing what was typed. Case, spaces, {@code -} and {@code _} are ignored,
 * so {@code World-Nether} finds {@code world_nether}. A word that resembles nothing gets nothing,
 * rather than the least bad guess.
 */
public final class Closest {

    private Closest() {
    }

    /** Up to {@code limit} of {@code options} close to {@code typed}, closest first, ties alphabetically. */
    public static List<String> to(String typed, Collection<String> options, int limit) {
        String wanted = joined(typed == null ? "" : typed);
        if (wanted.isEmpty() || limit <= 0 || options == null) {
            return List.of();
        }
        int tolerance = Math.max(2, wanted.length() / 3);
        Map<String, Integer> apartBy = new HashMap<>();
        for (String option : options) {
            if (option == null) {
                continue;
            }
            String candidate = joined(option);
            int apart = distance(wanted, candidate);
            boolean contains = wanted.length() >= 3 && candidate.contains(wanted);
            if (apart <= tolerance || contains) {
                apartBy.merge(option, contains ? Math.min(apart, candidate.length() - wanted.length()) : apart, Math::min);
            }
        }
        return apartBy.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** How many single-letter edits apart two words are; a swapped pair of neighbours is one. */
    public static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + change);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** Lower case, letters and digits only. */
    public static String joined(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
