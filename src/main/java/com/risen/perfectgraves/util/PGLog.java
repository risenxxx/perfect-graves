package com.risen.perfectgraves.util;

import com.risen.perfectgraves.PerfectGraves;

// Scoped logger helpers. Every line is prefixed `[pg.<scope>]` so log tails can be filtered by
// subsystem (e.g., `grep pg.cascade` shows only placement flow). Use the constants to stay
// consistent; adding new scopes is fine but keep them short and lowercase.
//
// Level guidance from the plan:
//   INFO  — user-relevant success narrative (grave placed, provider registered, etc.)
//   WARN  — degraded paths (fallback cascade, claim denial, death loop, config warnings)
//   ERROR — true failure (items lost)
//   DEBUG — per-step detail a developer would want but an admin wouldn't
public final class PGLog {

    public static final String DEATH       = "death";
    public static final String CASCADE     = "cascade";
    public static final String SHELL       = "shell";
    public static final String PLATFORM    = "platform";
    public static final String CLAIMS      = "claims";
    public static final String ACCESSORIES = "accessories";
    public static final String VGRAVE      = "vgrave";
    public static final String SOULBOUND   = "soulbound";
    public static final String GRAVE       = "grave";
    public static final String XP          = "xp";
    public static final String CONFIG      = "config";

    private PGLog() {}

    public static void info(String scope, String message, Object... args) {
        PerfectGraves.LOGGER.info("[pg." + scope + "] " + message, args);
    }

    public static void warn(String scope, String message, Object... args) {
        PerfectGraves.LOGGER.warn("[pg." + scope + "] " + message, args);
    }

    public static void warn(String scope, String message, Throwable t) {
        PerfectGraves.LOGGER.warn("[pg." + scope + "] " + message, t);
    }

    public static void error(String scope, String message, Object... args) {
        PerfectGraves.LOGGER.error("[pg." + scope + "] " + message, args);
    }

    public static void debug(String scope, String message, Object... args) {
        PerfectGraves.LOGGER.debug("[pg." + scope + "] " + message, args);
    }
}
