package com.risen.perfectgraves.util;

// Concise relative-time formatting like Xaero's: "5s", "3m", "2h 14m", "5d 3h". Skipping the
// smaller unit when it's zero ("3h" instead of "3h 00m") keeps marker text readable at small
// scale. Originally lived in GraveHologramRenderer; extracted so the death-history menus can
// reuse the same vocabulary.
public final class AgeFormat {

    private AgeFormat() {}

    public static String formatAge(long seconds) {
        if (seconds < 0L) seconds = 0L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m";
        long hours = minutes / 60L;
        if (hours < 24L) {
            long m = minutes % 60L;
            return m > 0 ? hours + "h " + m + "m" : hours + "h";
        }
        long days = hours / 24L;
        long h = hours % 24L;
        return h > 0 ? days + "d " + h + "h" : days + "d";
    }
}
