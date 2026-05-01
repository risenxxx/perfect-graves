package com.risen.perfectgraves.death;

import com.risen.perfectgraves.config.PGConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Detects "death loops" where a player dies repeatedly within a small radius and short time
// window (e.g., falling into the same lava pool after respawn). On detection, the cascade skips
// physical placement and routes straight to virtual grave, since placing a grave at a fatal
// spot just causes another death and another grave attempt.
public final class DeathLoopDetector {

    public record DeathRecord(BlockPos pos, ResourceKey<Level> dim, long gameTime) {}

    private static final Map<UUID, DeathRecord> LAST_DEATHS = new ConcurrentHashMap<>();

    private DeathLoopDetector() {}

    public static boolean check(ServerPlayer player, BlockPos deathPos) {
        if (!PGConfig.COMMON.deathLoopDetectionEnabled.get()) return false;

        DeathRecord prev = LAST_DEATHS.get(player.getUUID());
        if (prev == null) return false;
        if (!prev.dim().equals(player.level().dimension())) return false;

        long now = player.level().getGameTime();
        if (now - prev.gameTime() > PGConfig.COMMON.deathLoopTimeWindowTicks.get()) return false;

        int radius = PGConfig.COMMON.deathLoopRadiusBlocks.get();
        return prev.pos().distSqr(deathPos) <= (double) radius * radius;
    }

    public static void record(ServerPlayer player, BlockPos deathPos) {
        LAST_DEATHS.put(player.getUUID(),
            new DeathRecord(deathPos, player.level().dimension(), player.level().getGameTime()));
    }

    public static void clear(UUID uuid) {
        LAST_DEATHS.remove(uuid);
    }

    public static void clearAll() {
        LAST_DEATHS.clear();
    }
}
