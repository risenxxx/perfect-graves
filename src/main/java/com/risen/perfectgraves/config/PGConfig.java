package com.risen.perfectgraves.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class PGConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Common COMMON;
    public static final Client CLIENT;

    static {
        var builder = new ForgeConfigSpec.Builder();
        COMMON = new Common(builder);
        SPEC = builder.build();

        var clientBuilder = new ForgeConfigSpec.Builder();
        CLIENT = new Client(clientBuilder);
        CLIENT_SPEC = clientBuilder.build();
    }

    private PGConfig() {}

    public static final class Common {

        public final ForgeConfigSpec.IntValue maxSearchRadius;
        public final ForgeConfigSpec.IntValue maxSearchTimeMs;
        public final ForgeConfigSpec.IntValue topNCandidates;
        public final ForgeConfigSpec.IntValue maxVerticalCorrection;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> replaceableBlocks;
        public final ForgeConfigSpec.BooleanValue allowWaterloggedGraves;

        public final ForgeConfigSpec.BooleanValue respectClaims;
        public final ForgeConfigSpec.BooleanValue allowOwnClaims;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> enabledIntegrations;

        public final ForgeConfigSpec.BooleanValue allowPlatformGeneration;
        public final ForgeConfigSpec.ConfigValue<String> voidPlatformBlock;
        public final ForgeConfigSpec.ConfigValue<String> lavaPlatformBlock;
        public final ForgeConfigSpec.IntValue voidGravePlatformY;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> unreplaceableBlocks;

        public final ForgeConfigSpec.EnumValue<FallbackMode> fallbackMode;
        public final ForgeConfigSpec.BooleanValue safeDropAvoidLava;
        public final ForgeConfigSpec.BooleanValue safeDropAvoidVoid;
        public final ForgeConfigSpec.BooleanValue looseDropMarkerEnabled;
        public final ForgeConfigSpec.IntValue looseDropMarkerTtlSeconds;

        public final ForgeConfigSpec.IntValue protectionTimeSeconds;
        public final ForgeConfigSpec.IntValue selfDestructTimeSeconds;
        public final ForgeConfigSpec.BooleanValue pauseProtectionWhileOffline;
        public final ForgeConfigSpec.EnumValue<SelfDestructMode> onSelfDestruct;

        public final ForgeConfigSpec.BooleanValue virtualGravesEnabled;
        public final ForgeConfigSpec.IntValue restoreCommandPermissionLevel;
        public final ForgeConfigSpec.IntValue maxGravesPerPlayer;

        public final ForgeConfigSpec.ConfigValue<List<? extends String>> soulboundEnchantments;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> soulboundNbtTags;

        public final ForgeConfigSpec.BooleanValue saveXp;
        public final ForgeConfigSpec.IntValue xpSavePercentage;

        public final ForgeConfigSpec.BooleanValue deathLoopDetectionEnabled;
        public final ForgeConfigSpec.IntValue deathLoopTimeWindowTicks;
        public final ForgeConfigSpec.IntValue deathLoopRadiusBlocks;

        public final ForgeConfigSpec.BooleanValue deathHistoryEnabled;
        public final ForgeConfigSpec.IntValue deathHistoryMaxPerPlayer;
        public final ForgeConfigSpec.IntValue deathHistoryCommandPermissionLevel;

        public final ForgeConfigSpec.ConfigValue<List<? extends String>> dimensionOverrides;

        Common(ForgeConfigSpec.Builder b) {
            b.push("search");
            maxSearchRadius = b.comment("Max cube-shell radius explored when placing a grave.").defineInRange("maxSearchRadius", 15, 1, 128);
            maxSearchTimeMs = b.comment("Wall-clock budget for the shell search, in milliseconds. Exceeding this falls to safe-drop/virtual grave.").defineInRange("maxSearchTimeMs", 10, 1, 500);
            topNCandidates = b.comment("Early-exit once this many valid candidates are found.").defineInRange("topNCandidates", 5, 1, 64);
            maxVerticalCorrection = b.comment("Max blocks of upward correction when death occurs inside solids/lava.").defineInRange("maxVerticalCorrection", 20, 0, 128);
            replaceableBlocks = b.comment("Blocks that are safe to replace with a grave.").defineListAllowEmpty("replaceableBlocks", List.of(
                "minecraft:air",
                "minecraft:cave_air",
                "minecraft:grass",
                "minecraft:tall_grass",
                "minecraft:snow"
            ), o -> o instanceof String);
            allowWaterloggedGraves = b.comment(
                "If true, the grave can be placed inside water cells (waterlogged), preserving",
                "the water column instead of replacing it — the typical UX for drowning deaths.",
                "Requires a sturdy block directly beneath, so graves never float in mid-water.",
                "If false, water cells are not valid grave spots; drowning deaths fall through to",
                "the configured fallback (typically virtual grave)."
            ).define("allowWaterloggedGraves", true);
            b.pop();

            b.push("claims");
            respectClaims = b.comment("If true, the grave placement search consults loaded claim-mod integrations.").define("respectClaims", true);
            allowOwnClaims = b.comment("If true, graves may be placed inside claims the player owns.").define("allowOwnClaims", true);
            enabledIntegrations = b.comment(
                "Claim-mod integrations that may register providers at startup. Match against the mod's",
                "modId. Removing an entry here disables that integration even if the mod is installed."
            ).defineListAllowEmpty("enabledIntegrations", List.of(
                "ftbchunks",
                "openpartiesandclaims",
                "flan"
            ), o -> o instanceof String);
            b.pop();

            b.push("platform");
            allowPlatformGeneration = b.comment("Allow generating a 3x3 platform for lava/void deaths.").define("allowPlatformGeneration", true);
            voidPlatformBlock = b.comment("Block used for void-death platforms.").define("voidPlatformBlock", "minecraft:obsidian");
            lavaPlatformBlock = b.comment("Block used for lava-death platforms.").define("lavaPlatformBlock", "minecraft:obsidian");
            voidGravePlatformY = b.comment("Default Y for void platforms when SafePosTracker has nothing.").defineInRange("voidGravePlatformY", 64, -64, 320);
            unreplaceableBlocks = b.comment("Blocks that platform generation refuses to overwrite.").defineListAllowEmpty("unreplaceableBlocks", List.of(
                "minecraft:bedrock",
                "minecraft:barrier",
                "minecraft:end_portal",
                "minecraft:end_portal_frame",
                "minecraft:nether_portal"
            ), o -> o instanceof String);
            b.pop();

            b.push("fallback");
            fallbackMode = b.comment("What to do when grave placement can't find any valid spot.").defineEnum("mode", FallbackMode.SAFE_DROP);
            safeDropAvoidLava = b.comment("When picking a safe-drop spot, reject positions adjacent to lava.").define("safeDropAvoidLava", true);
            safeDropAvoidVoid = b.comment("When picking a safe-drop spot, reject positions near the void (min-build-height).").define("safeDropAvoidVoid", true);
            looseDropMarkerEnabled = b.comment(
                "If true, spawn a long-distance death marker at the SAFE_DROP / VANILLA_DROP",
                "position so the player still has a waypoint when no grave block was placed.",
                "Markers are dismissed per-viewer when the player gets close to them."
            ).define("looseDropMarkerEnabled", true);
            looseDropMarkerTtlSeconds = b.comment(
                "Server-side TTL for loose-drop markers, in seconds. After this, the marker entity",
                "self-discards and the marker disappears for everyone. 0 disables auto-eviction."
            ).defineInRange("looseDropMarkerTtlSeconds", 604800, 0, Integer.MAX_VALUE);
            b.pop();

            b.push("timers");
            protectionTimeSeconds = b.comment("Owner-only protection duration in seconds. 0 disables.").defineInRange("protectionTimeSeconds", 600, 0, Integer.MAX_VALUE);
            selfDestructTimeSeconds = b.comment("Grave self-destruct timeout in seconds. 0 disables.").defineInRange("selfDestructTimeSeconds", 86400, 0, Integer.MAX_VALUE);
            pauseProtectionWhileOffline = b.comment("Pause protection countdown while the owner is offline.").define("pauseProtectionWhileOffline", true);
            onSelfDestruct = b.comment(
                "What happens when the self-destruct timer fires. VIRTUAL_GRAVE packs contents into a",
                "virtual grave the owner can restore via /grave list. DESTROY discards items."
            ).defineEnum("onSelfDestruct", SelfDestructMode.VIRTUAL_GRAVE);
            b.pop();

            b.push("virtualGrave");
            virtualGravesEnabled = b.define("enabled", true);
            restoreCommandPermissionLevel = b.comment("Permission level required to /grave restore your own graves. 0 = all, 2 = OP.").defineInRange("restoreCommandPermissionLevel", 0, 0, 4);
            maxGravesPerPlayer = b.comment("Max virtual graves retained per player. Oldest evicted first.").defineInRange("maxGravesPerPlayer", 10, 1, 1000);
            b.pop();

            b.push("soulbound");
            // Defaults cover the two most common 1.20.1 Forge sources. Match is exact string
            // compare against the enchantment registry name — admins can add IDs for other mods
            // (e.g., ensorcellation:soulbound) by editing this list in the generated TOML.
            // Quick way to find an ID in-game: enable advanced tooltips (F3+H) and hover the book,
            // or run `/data get entity @s SelectedItem` to see the StoredEnchantments NBT directly.
            soulboundEnchantments = b.comment("Enchantment IDs whose presence on an item keeps it through death.")
                .defineListAllowEmpty("enchantments", List.of(
                    "enderio:soulbound",
                    "ars_elemental:soulbound"
                ), o -> o instanceof String);
            soulboundNbtTags = b.comment("NBT boolean flags on an item's root tag that mark it as soulbound.")
                .defineListAllowEmpty("nbtTags", List.of("Soulbound"), o -> o instanceof String);
            b.pop();

            b.push("xp");
            saveXp = b.define("saveXp", true);
            xpSavePercentage = b.defineInRange("xpSavePercentage", 100, 0, 100);
            b.pop();

            b.push("deathLoopDetection");
            deathLoopDetectionEnabled = b.define("enabled", true);
            deathLoopTimeWindowTicks = b.defineInRange("timeWindowTicks", 200, 20, 72000);
            deathLoopRadiusBlocks = b.defineInRange("radiusBlocks", 5, 0, 256);
            b.pop();

            b.push("deathHistory");
            deathHistoryEnabled = b.comment(
                "If true, every death recorded by the cascade is appended to a per-player history",
                "browsable via /grave history. Stored in world/data/perfectgraves_history.dat."
            ).define("enabled", true);
            deathHistoryMaxPerPlayer = b.comment(
                "Per-player ring buffer cap. Older entries are evicted oldest-first when this is",
                "exceeded. Each entry includes a full inventory snapshot at death time so the",
                "history stays accurate even after items are picked up or graves are destroyed."
            ).defineInRange("maxPerPlayer", 50, 1, 1000);
            deathHistoryCommandPermissionLevel = b.comment(
                "Permission level required to run /grave history (own deaths). 0 = anyone.",
                "/grave history <player> is always gated at level 2 (OP)."
            ).defineInRange("commandPermissionLevel", 0, 0, 4);
            b.pop();

            // Per-dimension overrides. ForgeConfigSpec doesn't natively support arbitrary-key
            // sub-tables, so overrides use a flat list of "dimension_id/key=value" strings.
            // Supported keys: maxSearchRadius (int), voidGravePlatformY (int),
            // voidPlatformBlock (block id), lavaPlatformBlock (block id).
            // Unknown keys are ignored silently; malformed entries are skipped with a WARN log.
            dimensionOverrides = b.comment(
                "Per-dimension overrides — one entry per line as \"<dim>/<key>=<value>\".",
                "Supported keys: maxSearchRadius, voidGravePlatformY, voidPlatformBlock, lavaPlatformBlock.",
                "Example: \"minecraft:the_nether/maxSearchRadius=10\""
            ).defineListAllowEmpty("dimensionOverrides", List.of(
                "minecraft:the_end/maxSearchRadius=20",
                "minecraft:the_end/voidGravePlatformY=64",
                "minecraft:the_nether/maxSearchRadius=10"
            ), o -> o instanceof String);
        }
    }

    // Long-distance grave marker — a skull + name floating above the grave, visible from much
    // further than the hologram. Each client picks its own setting because it's a render-only
    // preference; per-world / per-server config would just create UX friction.
    public static final class Client {

        public final ForgeConfigSpec.EnumValue<MarkerMode> markerMode;
        public final ForgeConfigSpec.IntValue markerMaxDistance;
        public final ForgeConfigSpec.BooleanValue markerOnlyOwnGraves;
        public final ForgeConfigSpec.DoubleValue looseDropDismissRadius;

        Client(ForgeConfigSpec.Builder b) {
            b.push("marker");
            markerMode = b.comment(
                "Long-distance grave marker visibility:",
                "  AUTO   — show unless another mod with in-world death markers is detected",
                "           (xaerominimap, journeymap, ftbchunks). 2D-only mods like VoxelMap or",
                "           AdvancedCompass don't trigger auto-suppression since they don't",
                "           duplicate the in-world rendering.",
                "  ALWAYS — always show, even when another death-marker mod is present (useful",
                "           when the other mod's marker is disabled in its own settings).",
                "  OFF    — never show; only the close-range hologram remains."
            ).defineEnum("mode", MarkerMode.AUTO);
            markerMaxDistance = b.comment(
                "Max distance (blocks) at which the long-distance marker is visible. The",
                "close-range hologram still takes over below ~16 blocks regardless. Set to 0",
                "for unlimited (matches JourneyMap's convention; the marker stays visible as",
                "long as the chunk is loaded on the client)."
            ).defineInRange("maxDistance", 256, 0, 100000);
            markerOnlyOwnGraves = b.comment(
                "If true, only the current player's own graves render the marker. False shows",
                "every nearby grave's marker (useful for ops/admins watching a server)."
            ).define("onlyOwnGraves", true);
            looseDropDismissRadius = b.comment(
                "Distance (blocks) at which a loose-drop marker is dismissed for the local player",
                "and stops rendering. Dismissal is per-viewer and persistent — once you walk close",
                "to a marker, it stays gone for you even if other players still see it."
            ).defineInRange("looseDropDismissRadius", 8.0, 1.0, 64.0);
            b.pop();
        }
    }

    public enum MarkerMode {
        AUTO,
        ALWAYS,
        OFF
    }

    public enum SelfDestructMode {
        VIRTUAL_GRAVE,
        DESTROY
    }

    public enum FallbackMode {
        SAFE_DROP,
        VANILLA_DROP,
        VIRTUAL_GRAVE,
        RETURN_TO_INVENTORY
    }
}
