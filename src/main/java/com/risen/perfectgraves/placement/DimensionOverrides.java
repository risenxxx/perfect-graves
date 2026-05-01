package com.risen.perfectgraves.placement;

import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.death.DeathContext;
import com.risen.perfectgraves.util.PGLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Per-dimension override resolver. Parses the flat "dim_id/key=value" list from config once,
// caches it until the config list reference changes (cheap ref-equality check handles reloads
// because ForgeConfigSpec returns a new List instance after a config reload).
public final class DimensionOverrides {

    private static volatile Map<ResourceKey<Level>, Map<String, String>> cache = Map.of();
    private static volatile List<? extends String> lastRaw = null;

    private DimensionOverrides() {}

    public static int maxSearchRadius(Level level) {
        return getInt(level.dimension(), "maxSearchRadius")
            .orElseGet(PGConfig.COMMON.maxSearchRadius::get);
    }

    public static int voidGravePlatformY(Level level) {
        return getInt(level.dimension(), "voidGravePlatformY")
            .orElseGet(PGConfig.COMMON.voidGravePlatformY::get);
    }

    // Vanilla's `ServerLevel.makeObsidianPlatform` rebuilds a 5x5 obsidian platform centered at
    // (100, 49, 0) every time a player teleports into The End. Anything we place in that box
    // gets wiped on next entry — items lost. The check below is generous (radius 8 horizontally,
    // y 45..53) so a grave placed adjacent to the platform isn't clipped by the regen either.
    public static boolean isInEndSpawnArea(ResourceKey<Level> dim, BlockPos pos) {
        if (!dim.equals(Level.END)) return false;
        int dx = Math.abs(pos.getX() - 100);
        int dz = Math.abs(pos.getZ());
        int y = pos.getY();
        return dx <= 8 && dz <= 8 && y >= 45 && y <= 53;
    }

    public static String platformBlockId(Level level, DeathContext.DeathType type) {
        String key = (type == DeathContext.DeathType.LAVA) ? "lavaPlatformBlock" : "voidPlatformBlock";
        return getString(level.dimension(), key).orElseGet(() ->
            (type == DeathContext.DeathType.LAVA)
                ? PGConfig.COMMON.lavaPlatformBlock.get()
                : PGConfig.COMMON.voidPlatformBlock.get()
        );
    }

    public static Optional<Integer> getInt(ResourceKey<Level> dim, String key) {
        String v = lookup().getOrDefault(dim, Map.of()).get(key);
        if (v == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            PGLog.warn(PGLog.CONFIG, "dimensionOverrides: {}:{} has non-integer value \"{}\"", dim.location(), key, v);
            return Optional.empty();
        }
    }

    public static Optional<String> getString(ResourceKey<Level> dim, String key) {
        return Optional.ofNullable(lookup().getOrDefault(dim, Map.of()).get(key));
    }

    private static Map<ResourceKey<Level>, Map<String, String>> lookup() {
        List<? extends String> raw = PGConfig.COMMON.dimensionOverrides.get();
        if (raw != lastRaw) {
            synchronized (DimensionOverrides.class) {
                if (raw != lastRaw) {
                    cache = parse(raw);
                    lastRaw = raw;
                }
            }
        }
        return cache;
    }

    private static Map<ResourceKey<Level>, Map<String, String>> parse(List<? extends String> raw) {
        Map<ResourceKey<Level>, Map<String, String>> out = new HashMap<>();
        for (String entry : raw) {
            int slash = entry.indexOf('/');
            int eq = entry.indexOf('=');
            if (slash <= 0 || eq <= slash + 1) {
                PGLog.warn(PGLog.CONFIG, "malformed dimensionOverrides entry: \"{}\" (expected dim_id/key=value)", entry);
                continue;
            }
            String dim = entry.substring(0, slash).trim();
            String key = entry.substring(slash + 1, eq).trim();
            String value = entry.substring(eq + 1).trim();
            ResourceLocation rl = ResourceLocation.tryParse(dim);
            if (rl == null) {
                PGLog.warn(PGLog.CONFIG, "dimensionOverrides: invalid dimension id \"{}\"", dim);
                continue;
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, rl);
            out.computeIfAbsent(dimKey, k -> new HashMap<>()).put(key, value);
        }
        return out;
    }
}
