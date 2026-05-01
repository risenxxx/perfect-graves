package com.risen.perfectgraves.util;

public final class NbtKeys {

    private static final String NS = "perfectgraves:";

    public static final String OWNER = NS + "owner";
    public static final String ITEMS = NS + "items";
    public static final String ITEMS_SIZE = NS + "items_size";
    public static final String XP = NS + "xp";
    public static final String DEATH_TIME = NS + "death_time";
    public static final String ONLINE_TICKS_ELAPSED = NS + "online_ticks_elapsed";
    public static final String PROTECTION_TOTAL_TICKS = NS + "protection_total_ticks";
    public static final String PROTECTION_EXPIRED = NS + "protection_expired";
    public static final String SELF_DESTRUCT_AT = NS + "self_destruct_at";
    // Derived client-facing field: projected game-time when protection ends, assuming the
    // owner stays online. Lets the client compute a smooth remaining-time countdown between
    // the infrequent BE re-syncs instead of freezing the display at the last synced value.
    public static final String PROTECTION_ENDS_AT_GAMETIME = NS + "protection_ends_at";
    // Server→client flag: is the protection timer currently paused (config = pause + owner
    // offline). Lets the client choose between smooth gameTime-extrapolation and frozen-display
    // formulas without needing the config value or making its own owner-online guess.
    public static final String PROTECTION_PAUSED = NS + "protection_paused";
    public static final String DEATH_MESSAGE = NS + "death_message";
    public static final String CURIOS_ORIGIN = NS + "curios_origin";

    private NbtKeys() {}
}
