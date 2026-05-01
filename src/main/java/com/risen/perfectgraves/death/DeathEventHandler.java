package com.risen.perfectgraves.death;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.accessories.ExtendedInventoryRegistry;
import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.history.DeathHistoryData;
import com.risen.perfectgraves.history.DeathHistoryEntry;
import com.risen.perfectgraves.placement.PlacementCascade;
import com.risen.perfectgraves.placement.SafePosTracker;
import com.risen.perfectgraves.soulbound.SoulboundFilter;
import com.risen.perfectgraves.soulbound.SoulboundTracker;
import com.risen.perfectgraves.util.NbtKeys;
import com.risen.perfectgraves.util.PGLog;
import com.risen.perfectgraves.virtual.VirtualGrave;
import com.risen.perfectgraves.virtual.VirtualGraveData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID)
public final class DeathEventHandler {

    private DeathEventHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (player.isCreative() || player.isSpectator()) {
            PGLog.debug(PGLog.DEATH, "{} early-exit: creative/spectator", player.getGameProfile().getName());
            return;
        }
        if (player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            PGLog.debug(PGLog.DEATH, "{} early-exit: keepInventory=true", player.getGameProfile().getName());
            return;
        }
        if (player.getInventory().isEmpty()) {
            PGLog.debug(PGLog.DEATH, "{} early-exit: empty inventory", player.getGameProfile().getName());
            return;
        }

        // DeathContext.classify detects lava/void, consumes the SafePosTracker snapshot for
        // void-death correction, and sets requiresPlatform when no natural landing spot exists.
        // Death loop detection runs BEFORE record() so we compare against the PREVIOUS death,
        // not this one. curiosSnapshot is Map.of() until step 13.
        BlockPos deathPos = player.blockPosition();
        boolean isDeathLoop = DeathLoopDetector.check(player, deathPos);
        DeathLoopDetector.record(player, deathPos);

        Optional<com.risen.perfectgraves.placement.SafePosData> safePos = SafePosTracker.get(player.getUUID());
        DeathContext ctx = DeathContext.classify(player.level(), player, safePos, isDeathLoop);
        long gameTime = player.level().getGameTime();

        if (isDeathLoop) {
            PGLog.warn(PGLog.DEATH, "{} death loop detected @ {} — cascade will truncate to virtual grave",
                player.getGameProfile().getName(), deathPos);
        }

        // Snapshot accessory slots at death time. Curios' own drop listener will add worn items
        // to event.getDrops() via onLivingDrops; we'll diff against this snapshot there to tag
        // which drops originated from which slot.
        Map<String, ItemStack> curiosSnapshot = ExtendedInventoryRegistry.snapshotAll(player);

        // Capture the vanilla-formatted death message the same way chat does — matches Universal
        // Graves' approach. DamageType effects handle fall-variant upgrades internally (e.g.
        // "fell from a high place" vs. "was doomed to fall by X"), so this produces rich messages
        // without needing to touch the combat tracker ourselves.
        net.minecraft.network.chat.Component deathMessage =
            event.getSource().getLocalizedDeathMessage(player);

        GraveProcessor.claim(player, ctx, safePos, curiosSnapshot, gameTime, deathMessage);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        long gameTime = player.level().getGameTime();
        var uuid = player.getUUID();
        String name = player.getGameProfile().getName();

        // Diagnostic: confirm handler fires and whether PendingDeath is present.
        PGLog.info(PGLog.DEATH, "{} onLivingDrops fired drops={} canceled={} uuid={}",
            name, event.getDrops().size(), event.isCanceled(), uuid);

        if (GraveProcessor.wasHandled(uuid, gameTime)) {
            PGLog.debug(PGLog.DEATH, "{} already handled; cancelling re-fire", name);
            event.setCanceled(true);
            return;
        }

        var pendingOpt = GraveProcessor.pending(uuid);
        if (pendingOpt.isEmpty()) {
            PGLog.warn(PGLog.DEATH, "{} onLivingDrops but no PendingDeath (cancelled upstream?)", name);
            return;
        }
        var pending = pendingOpt.get();

        // Copy stacks out of the ItemEntities so the curios-origin tagging below doesn't mutate
        // the originals — if the cascade ends in VANILLA_DROP, the event isn't cancelled and
        // vanilla drops the originals, which should stay tag-free.
        List<ItemStack> pool = new ArrayList<>(event.getDrops().size());
        for (ItemEntity drop : event.getDrops()) {
            pool.add(drop.getItem().copy());
        }

        // Curios-origin tagging: diff against the death-time snapshot and stamp matching stacks
        // with a perfectgraves:curios_origin NBT tag. Runs BEFORE the soulbound filter so items
        // that get diverted to SoulboundTracker still carry the tag and can be restored to the
        // right accessory slot on respawn.
        Map<String, ItemStack> curiosSnapshot = pending.curiosSnapshot();
        if (!curiosSnapshot.isEmpty()) {
            for (ItemStack stack : pool) {
                ExtendedInventoryRegistry.identify(stack, curiosSnapshot).ifPresent(slotId ->
                    stack.getOrCreateTag().putString(NbtKeys.CURIOS_ORIGIN, slotId)
                );
            }
        }

        // Soulbound filter: divert configured-soulbound items into SoulboundTracker. They skip
        // the cascade entirely and are restored to the player on PlayerEvent.Clone (respawn)
        // or PlayerLoggedInEvent (reconnect after disconnect-on-death-screen).
        List<ItemStack> soulbound = SoulboundFilter.split(pool);
        if (!soulbound.isEmpty()) {
            SoulboundTracker.store(uuid, soulbound);
            PGLog.info(PGLog.SOULBOUND, "{} diverted {} items for respawn restore",
                player.getGameProfile().getName(), soulbound.size());
        }

        // XP from the player is still intact at LOWEST Drops (vanilla drops XP separately, after
        // dropAllDeathLoot returns). Applying vanilla's capped formula keeps us aligned with
        // normal server behaviour; a later step may replace this with the authoritative value
        // from LivingExperienceDropEvent if mods mutate it.
        int savedXp = 0;
        if (PGConfig.COMMON.saveXp.get()) {
            int rawXp = Math.min(player.experienceLevel * 7, 100);
            savedXp = rawXp * PGConfig.COMMON.xpSavePercentage.get() / 100;
        }

        // Skip placement when there's nothing to preserve. Happens when every item the player
        // was carrying is soulbound (ours or a third-party mod's) AND there's no xp to save —
        // running the cascade would still produce a grave block, just empty, which is just
        // misleading clutter. Cancel the event so any soulbound ItemEntities still riding in
        // event.getDrops() don't get dropped to the ground (the player will get them back via
        // SoulboundTracker on respawn).
        if (pool.isEmpty() && savedXp == 0) {
            PGLog.info(PGLog.CASCADE, "{} skipping placement: no items or xp to preserve (all soulbound or empty)",
                name);
            event.setCanceled(true);
            GraveProcessor.markHandled(uuid, gameTime);
            return;
        }

        PlacementCascade.Outcome outcome = GraveProcessor.run(player, pool, savedXp, pending);
        CascadeResult result = outcome.result();
        GraveProcessor.recordResult(uuid, result);

        // Append to per-player death history. Items snapshot is the same `pool` we just fed the
        // cascade — captures everything that would have dropped (mod-added drops included).
        // Virtual-grave UUID lookup peeks the most-recently-stored entry for the player; the
        // cascade just placed it, so it's the newest one in the byOwner list. No extra disk
        // hits — DeathHistoryData lives in memory until the world saves.
        if (PGConfig.COMMON.deathHistoryEnabled.get()) {
            recordHistoryEntry(player, pool, savedXp, pending, outcome);
        }

        if (result == CascadeResult.VANILLA_DROP) {
            // Leave event.getDrops() untouched; vanilla drops naturally. XP handler will clear PendingDeath.
            return;
        }

        // Items went to grave/safe-drop/virtual/return — stop vanilla from also dropping.
        // Transfer into the chosen destination happens in steps 3+ as each destination is implemented.
        event.setCanceled(true);
        GraveProcessor.markHandled(uuid, gameTime);
    }

    private static void recordHistoryEntry(ServerPlayer player, List<ItemStack> pool, int savedXp,
                                           PendingDeath pending, PlacementCascade.Outcome outcome) {
        java.util.Optional<net.minecraft.core.BlockPos> blockRef = java.util.Optional.empty();
        java.util.Optional<java.util.UUID> virtualRef = java.util.Optional.empty();
        switch (outcome.result()) {
            case GRAVE, SAFE_DROP -> blockRef = outcome.location();
            case VIRTUAL_GRAVE, RETURN_TO_INVENTORY -> {
                // Newest virtual grave for this player is the one the cascade just stored.
                List<VirtualGrave> stored = VirtualGraveData.get(player.getServer())
                    .getFor(player.getUUID());
                if (!stored.isEmpty()) {
                    virtualRef = java.util.Optional.of(stored.get(stored.size() - 1).id());
                }
            }
            default -> {} // VANILLA_DROP: items land near deathPos, already stored.
        }

        // Copy the pool one more time so the history snapshot doesn't share stack instances
        // with the cascade (which may further mutate them, e.g. placing in grave BE).
        List<ItemStack> snapshot = new ArrayList<>(pool.size());
        for (ItemStack s : pool) {
            if (!s.isEmpty()) snapshot.add(s.copy());
        }

        net.minecraft.network.chat.Component cause = pending.deathMessage();
        String causeJson = cause == null ? "" : net.minecraft.network.chat.Component.Serializer.toJson(cause);

        DeathHistoryEntry entry = new DeathHistoryEntry(
            java.util.UUID.randomUUID(),
            player.getUUID(),
            player.getGameProfile().getName(),
            System.currentTimeMillis(),
            pending.gameTime(),
            pending.context().dimension(),
            pending.context().deathPos(),
            causeJson,
            outcome.result(),
            blockRef,
            virtualRef,
            snapshot,
            savedXp
        );
        DeathHistoryData.get(player.getServer()).add(entry);
        PGLog.debug(PGLog.DEATH, "{} history append: outcome={} items={} xp={}",
            player.getGameProfile().getName(), outcome.result(), snapshot.size(), savedXp);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        var uuid = player.getUUID();

        // Correction-18 revisited: in vanilla 1.20.1 `LivingEntity.dropAllDeathLoot()` fires
        // `LivingExperienceDropEvent` BEFORE `LivingDropsEvent`. Consuming PendingDeath here
        // (as the original plan assumed) would leave Drops empty-handed and the cascade would
        // never run. Instead we PEEK:
        //   - If cascade already ran (cascadeResult present) → consume now, decide from result.
        //   - If cascade hasn't run yet (result absent) → don't consume. Drops handler owns it.
        //     Cancel the event optimistically: the typical outcome is a non-vanilla destination
        //     that will store XP in the grave; if cascade ends up VANILLA_DROP, XP is lost but
        //     items still drop (acceptable tradeoff for a fallback path).
        Optional<PendingDeath> peek = GraveProcessor.pending(uuid);
        if (peek.isEmpty()) return;                                     // not a death we're tracking

        Optional<CascadeResult> result = peek.get().cascadeResult();

        if (result.isPresent()) {
            GraveProcessor.consume(uuid);
            if (result.get() == CascadeResult.VANILLA_DROP) return;
            if (!PGConfig.COMMON.saveXp.get()) return;
            PGLog.debug(PGLog.XP, "{} cancel vanilla XP (cascade={})",
                player.getGameProfile().getName(), result.get());
            event.setCanceled(true);
            return;
        }

        // Pre-Drops path: Drops will read PendingDeath next. We only cancel XP.
        if (!PGConfig.COMMON.saveXp.get()) return;
        PGLog.debug(PGLog.XP, "{} cancel vanilla XP (cascade pending)",
            player.getGameProfile().getName());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        long now = server.overworld().getGameTime();
        GraveProcessor.evictExpired(now);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        GraveProcessor.clear(player.getUUID());
        DeathLoopDetector.clear(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GraveProcessor.clearAll();
        DeathLoopDetector.clearAll();
    }
}
