package com.risen.perfectgraves.death;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.placement.PlacementCascade;
import com.risen.perfectgraves.placement.SafePosData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GraveProcessor {

    // 5 seconds at 20 TPS. Covers corrections 11, 19: PendingDeath + handledDeaths share this TTL.
    private static final long TTL_TICKS = 100L;

    private static final Map<UUID, PendingDeath> pending = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> handled = new ConcurrentHashMap<>();

    private GraveProcessor() {}

    public static void claim(ServerPlayer player, DeathContext ctx, Optional<SafePosData> safePos,
                             Map<String, ItemStack> curios, long gameTime, @Nullable Component deathMessage) {
        pending.put(player.getUUID(), new PendingDeath(ctx, safePos, curios, gameTime, deathMessage, Optional.empty()));
        PerfectGraves.LOGGER.info("[pg.death] {} claim stored; uuid={} pending-size={} keys={} map-id=0x{} cls-id=0x{}",
            player.getGameProfile().getName(), player.getUUID(), pending.size(), pending.keySet(),
            Integer.toHexString(System.identityHashCode(pending)),
            Integer.toHexString(System.identityHashCode(GraveProcessor.class)));
    }

    public static Optional<PendingDeath> pending(UUID id) {
        PerfectGraves.LOGGER.info("[pg.death] pending() read uuid={} size={} keys={} map-id=0x{} cls-id=0x{}",
            id, pending.size(), pending.keySet(),
            Integer.toHexString(System.identityHashCode(pending)),
            Integer.toHexString(System.identityHashCode(GraveProcessor.class)));
        return Optional.ofNullable(pending.get(id));
    }

    public static void recordResult(UUID id, CascadeResult result) {
        pending.computeIfPresent(id, (k, existing) -> existing.withResult(result));
    }

    public static Optional<PendingDeath> consume(UUID id) {
        PendingDeath removed = pending.remove(id);
        PerfectGraves.LOGGER.info("[pg.death] consume({}) called; wasPresent={} newSize={}",
            id, removed != null, pending.size());
        return Optional.ofNullable(removed);
    }

    public static boolean wasHandled(UUID id, long gameTime) {
        Long ts = handled.get(id);
        return ts != null && gameTime - ts <= TTL_TICKS;
    }

    public static void markHandled(UUID id, long gameTime) {
        handled.put(id, gameTime);
    }

    public static PlacementCascade.Outcome run(ServerPlayer player, List<ItemStack> pool, int xp, PendingDeath entry) {
        return PlacementCascade.execute(
            player, pool, xp, entry.context(), entry.gameTime(), entry.deathMessage());
    }

    public static void evictExpired(long now) {
        int beforeP = pending.size();
        int beforeH = handled.size();
        pending.entrySet().removeIf(e -> now - e.getValue().gameTime() > TTL_TICKS);
        handled.entrySet().removeIf(e -> now - e.getValue() > TTL_TICKS);
        int ep = beforeP - pending.size();
        int eh = beforeH - handled.size();
        if (ep > 0 || eh > 0) {
            PerfectGraves.LOGGER.info("[pg.death] sweeper evicted pending={} handled={} now={} map-id=0x{}",
                ep, eh, now, Integer.toHexString(System.identityHashCode(pending)));
        }
    }

    public static void clear(UUID id) {
        int beforeSize = pending.size();
        pending.remove(id);
        handled.remove(id);
        PerfectGraves.LOGGER.info("[pg.death] clear({}) called; pending went {} → {}",
            id, beforeSize, pending.size(),
            new Exception("stacktrace"));
    }

    public static void clearAll() {
        int beforeSize = pending.size();
        pending.clear();
        handled.clear();
        if (beforeSize > 0) {
            PerfectGraves.LOGGER.info("[pg.death] clearAll() called; pending went {} → 0",
                beforeSize, new Exception("stacktrace"));
        }
    }
}
