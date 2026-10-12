package com.risen.perfectgraves.death;

import com.risen.perfectgraves.placement.PlacementCascade;
import com.risen.perfectgraves.placement.SafePosData;
import com.risen.perfectgraves.util.PGLog;
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

    // uuid → the gameTime of the death whose drops we have already routed. The VALUE is a death
    // identity, not a "last seen" timestamp — see wasHandled.
    private static final Map<UUID, Long> handled = new ConcurrentHashMap<>();

    private GraveProcessor() {}

    public static void claim(ServerPlayer player, DeathContext ctx, Optional<SafePosData> safePos,
                             Map<String, ItemStack> curios, long gameTime, @Nullable Component deathMessage) {
        pending.put(player.getUUID(), new PendingDeath(ctx, safePos, curios, gameTime, deathMessage, Optional.empty()));
        PGLog.debug(PGLog.DEATH, "{} claim stored for death @ tick {}",
            player.getGameProfile().getName(), gameTime);
    }

    public static Optional<PendingDeath> pending(UUID id) {
        return Optional.ofNullable(pending.get(id));
    }

    public static void recordResult(UUID id, CascadeResult result) {
        pending.computeIfPresent(id, (k, existing) -> existing.withResult(result));
    }

    public static Optional<PendingDeath> consume(UUID id) {
        return Optional.ofNullable(pending.remove(id));
    }

    // Has the drops event for THIS death already been routed?
    //
    // The key is the death's own gameTime (from its PendingDeath), not "did this player die at
    // some point in the last TTL_TICKS". The window form was a silent item-loss bug: a player who
    // died twice inside 5 seconds — respawning into lava, into a mob-infested spawn point, or back
    // into the void — had the second death's entire inventory cancelled with no grave, no virtual
    // grave and no death-history entry. It also pre-empted death-loop detection, whose own window
    // is 200 ticks, so the feature written for exactly that situation never got to run.
    //
    // Keying on the death tick still catches the case this guard exists for (another mod re-firing
    // LivingDropsEvent for a death we already handled), including a re-fire a tick or two later,
    // because a re-fire carries the same PendingDeath and therefore the same death tick.
    public static boolean wasHandled(UUID id, long deathGameTime) {
        Long ts = handled.get(id);
        return ts != null && ts.longValue() == deathGameTime;
    }

    // Fallback for when the PendingDeath is already gone and the death tick is unreadable (the XP
    // handler consumed it, or the sweeper did). Safe as a time window here precisely because a
    // genuinely NEW death always has a live PendingDeath: claim() runs at HIGHEST on
    // LivingDeathEvent, in the same tick as the drops event that follows it.
    public static boolean handledRecently(UUID id, long now) {
        Long ts = handled.get(id);
        return ts != null && now - ts <= TTL_TICKS;
    }

    public static void markHandled(UUID id, long deathGameTime) {
        handled.put(id, deathGameTime);
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
            PGLog.debug(PGLog.DEATH, "sweeper evicted pending={} handled={} now={}", ep, eh, now);
        }
    }

    public static void clear(UUID id) {
        pending.remove(id);
        handled.remove(id);
    }

    public static void clearAll() {
        pending.clear();
        handled.clear();
    }
}
