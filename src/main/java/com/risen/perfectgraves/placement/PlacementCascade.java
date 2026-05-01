package com.risen.perfectgraves.placement;

import com.risen.perfectgraves.claims.ClaimRegistry;
import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.death.CascadeResult;
import com.risen.perfectgraves.death.DeathContext;
import com.risen.perfectgraves.grave.GraveBlockEntity;
import com.risen.perfectgraves.marker.LooseDropMarkerEntity;
import com.risen.perfectgraves.registry.PGBlocks;
import com.risen.perfectgraves.util.PGLog;
import com.risen.perfectgraves.virtual.VirtualGrave;
import com.risen.perfectgraves.virtual.VirtualGraveData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PlacementCascade {

    public record Outcome(CascadeResult result, Optional<BlockPos> location) {}

    private PlacementCascade() {}

    public static Outcome execute(ServerPlayer player, List<ItemStack> pool, int xp,
                                  DeathContext ctx, long gameTime, @Nullable Component deathMessage) {
        Level level = player.level();

        // Truncated cascade for death loops: the previous grave spot was also fatal, so placing
        // another physical grave there (or on a generated platform) just causes another death.
        // Skip all physical placement attempts; route straight to virtual grave so the owner can
        // manually restore once they're somewhere safe.
        if (ctx.isDeathLoop()) {
            if (storeVirtualGrave(player, pool, xp, ctx, deathMessage)) {
                PGLog.warn(PGLog.CASCADE, "{} death-loop truncation → virtual grave",
                    player.getGameProfile().getName());
                return new Outcome(CascadeResult.VIRTUAL_GRAVE, Optional.empty());
            }
            // virtual graves disabled in config — there's nothing safe to do; fall through to
            // vanilla drop as a last resort (items will likely re-die in the same pool, but that
            // is the server owner's choice via config).
            PGLog.error(PGLog.CASCADE, "{} death loop + virtual graves disabled; items will drop at death pos",
                player.getGameProfile().getName());
            return new Outcome(CascadeResult.VANILLA_DROP, Optional.empty());
        }

        int maxRadius = DimensionOverrides.maxSearchRadius(level);
        int topN = PGConfig.COMMON.topNCandidates.get();
        long budgetNanos = (long) PGConfig.COMMON.maxSearchTimeMs.get() * 1_000_000L;

        ClaimRegistry.SearchContext claimCtx = new ClaimRegistry.SearchContext();
        long startNanos = System.nanoTime();
        ShellSearch.Result sr = ShellSearch.search(level, player, ctx.correctedPos(), maxRadius, topN, budgetNanos, claimCtx);
        long elapsedNanos = System.nanoTime() - startNanos;

        String playerName = player.getGameProfile().getName();
        PGLog.debug(PGLog.SHELL, "{} search: {} candidates, safeDrop={}, {}ms, from={} type={} loop={}",
            playerName, sr.candidates().size(), sr.safeDrop().isPresent(),
            elapsedNanos / 1_000_000L, ctx.correctedPos(), ctx.type(), ctx.isDeathLoop());

        // 1. Try to place grave at the best shell-search candidate.
        for (BlockPos spot : sr.candidates()) {
            if (placeGrave(level, spot, player, pool, xp, gameTime, deathMessage)) {
                PGLog.info(PGLog.CASCADE, "{} step=1-shell grave @ {}", playerName, spot);
                return new Outcome(CascadeResult.GRAVE, Optional.of(spot));
            }
        }

        // 2. If the death context demands a platform (lava with no natural air ledge, or void
        //    without a stored safe pos), try building one — respecting the 9-block claim check
        //    and the unreplaceable-blocks guard.
        if (ctx.requiresPlatform()) {
            PlatformBuilder.Result pr = PlatformBuilder.tryPlace(level, ctx.correctedPos(), player, claimCtx, ctx.type());
            if (pr.placed() && placeGrave(level, ctx.correctedPos(), player, pool, xp, gameTime, deathMessage)) {
                PGLog.info(PGLog.CASCADE, "{} step=2-platform grave @ {}", playerName, ctx.correctedPos());
                return new Outcome(CascadeResult.GRAVE, Optional.of(ctx.correctedPos()));
            }
            if (pr.claimBlocked()) {
                PGLog.warn(PGLog.CASCADE, "{} platform claim-blocked; routing to virtual grave", playerName);
            }
        }

        // 3. Resolve fallback based on configured mode.
        var fallbackMode = PGConfig.COMMON.fallbackMode.get();

        // requiresPlatform with a platform-build failure: prefer virtual grave over safe-drop —
        // the safe-drop candidate near lava/void may be unsafe (lava tide, mob shove, etc.).
        if (ctx.requiresPlatform() && PGConfig.COMMON.virtualGravesEnabled.get()) {
            if (storeVirtualGrave(player, pool, xp, ctx, deathMessage)) {
                PGLog.warn(PGLog.CASCADE, "{} step=5-virtual (platform failed)", playerName);
                return new Outcome(CascadeResult.VIRTUAL_GRAVE, Optional.empty());
            }
        }

        if (fallbackMode == PGConfig.FallbackMode.VIRTUAL_GRAVE) {
            if (storeVirtualGrave(player, pool, xp, ctx, deathMessage)) {
                PGLog.warn(PGLog.CASCADE, "{} step=5-virtual (mode)", playerName);
                return new Outcome(CascadeResult.VIRTUAL_GRAVE, Optional.empty());
            }
        }

        if (fallbackMode == PGConfig.FallbackMode.SAFE_DROP && sr.safeDrop().isPresent()) {
            spawnSafeDrop(level, sr.safeDrop().get(), pool);
            spawnLooseDropMarker(level, sr.safeDrop().get(), player, gameTime, LooseDropMarkerEntity.DropKind.SAFE_DROP);
            PGLog.warn(PGLog.CASCADE, "{} step=3-safedrop @ {}", playerName, sr.safeDrop().get());
            return new Outcome(CascadeResult.SAFE_DROP, sr.safeDrop());
        }

        // Last-resort virtual grave for any non-VANILLA mode that hasn't found a spot. Rescues
        // SAFE_DROP-with-no-candidate (e.g., suffocation under the Nether bedrock ceiling where
        // shell search and safe-drop both come up empty) — without this, the cascade falls into
        // vanilla drop and items get extruded above bedrock, effectively lost. RETURN_TO_INVENTORY
        // also lands here since it's not wired as a distinct path.
        if (fallbackMode != PGConfig.FallbackMode.VANILLA_DROP) {
            if (storeVirtualGrave(player, pool, xp, ctx, deathMessage)) {
                PGLog.warn(PGLog.CASCADE, "{} step=5-virtual (physical options exhausted)", playerName);
                return new Outcome(CascadeResult.VIRTUAL_GRAVE, Optional.empty());
            }
        }

        // 4. Vanilla drop — admin chose VANILLA_DROP, or virtual graves are disabled in config.
        // DeathEventHandler sees VANILLA_DROP and will NOT cancel the drops event.
        spawnLooseDropMarker(level, ctx.deathPos(), player, gameTime, LooseDropMarkerEntity.DropKind.VANILLA_DROP);
        PGLog.warn(PGLog.CASCADE, "{} step=4-vanilla @ {}", playerName, ctx.deathPos());
        return new Outcome(CascadeResult.VANILLA_DROP, Optional.empty());
    }

    private static boolean placeGrave(Level level, BlockPos pos, ServerPlayer player,
                                      List<ItemStack> pool, int xp, long gameTime,
                                      @Nullable Component deathMessage) {
        BlockState state = PGBlocks.GRAVE.get().defaultBlockState();
        // Preserve the water column when placing in a SOURCE-water cell — vanilla waterlog pattern.
        // Source-only (isSource) so we don't promote a flowing/spillover cell into a full source
        // block (which would silently create infinite water near the grave). Flow cells fall back
        // to plain placement; the non-occluding model lets the surrounding flow continue past it.
        if (level.getFluidState(pos).isSource()
            && level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
            state = state.setValue(com.risen.perfectgraves.grave.GraveBlock.WATERLOGGED, true);
        }
        if (!level.setBlock(pos, state, Block.UPDATE_ALL)) return false;

        if (level.getBlockEntity(pos) instanceof GraveBlockEntity be) {
            be.setContents(pool, xp, player.getGameProfile(), gameTime, deathMessage);
            return true;
        }
        // Block placed but BE missing — unusual, but defensive: roll back and report failure.
        level.removeBlock(pos, false);
        return false;
    }

    private static boolean storeVirtualGrave(ServerPlayer player, List<ItemStack> pool, int xp,
                                             DeathContext ctx, @Nullable Component deathMessage) {
        if (!PGConfig.COMMON.virtualGravesEnabled.get()) return false;
        List<ItemStack> items = new ArrayList<>(pool.size());
        for (ItemStack s : pool) {
            if (!s.isEmpty()) items.add(s.copy());
        }
        String causeJson = deathMessage != null ? Component.Serializer.toJson(deathMessage) : "";
        VirtualGrave vg = new VirtualGrave(
            UUID.randomUUID(),
            player.getUUID(),
            player.getGameProfile().getName(),
            ctx.deathPos(),
            ctx.dimension(),
            System.currentTimeMillis(),
            items,
            xp,
            causeJson
        );
        VirtualGraveData.get(player.getServer()).add(vg);
        PGLog.info(PGLog.VGRAVE, "{} stored grave {} ({} items, {} xp)",
            player.getGameProfile().getName(), vg.id(), items.size(), xp);

        notifyVirtualGraveCreated(player);
        return true;
    }

    // Sends the styled "your items are in a virtual grave, use /grave list to retrieve them"
    // chat message. Reused by self-destruct conversion in GraveBlockEntity so a player whose
    // grave decayed into a virtual one while they were online doesn't have to figure that out
    // by accident. Self-destruct caller checks owner-online before invoking; this method just
    // emits the message.
    public static void notifyVirtualGraveCreated(ServerPlayer player) {
        // Click-to-suggest the retrieval command. Using SUGGEST_COMMAND (not RUN_COMMAND) so the
        // player has a moment to read it before pressing Enter — feels less abrupt than auto-run.
        Component cmd = Component.literal("/grave list").withStyle(s -> s
            .withColor(ChatFormatting.GOLD)
            .withBold(true)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/grave list"))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.translatable("perfectgraves.cascade.virtual_grave_created.hover"))));
        Component msg = Component.empty()
            .append(Component.literal("☠ ").withStyle(ChatFormatting.GOLD))
            .append(Component.translatable("perfectgraves.cascade.virtual_grave_created", cmd)
                .withStyle(ChatFormatting.WHITE));
        player.displayClientMessage(msg, false);
    }

    private static void spawnSafeDrop(Level level, BlockPos pos, List<ItemStack> pool) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.25, z = pos.getZ() + 0.5;
        for (ItemStack stack : pool) {
            if (stack.isEmpty()) continue;
            ItemEntity ie = new ItemEntity(level, x, y, z, stack.copy());
            ie.setDefaultPickUpDelay();
            level.addFreshEntity(ie);
        }
    }

    // Spawns the long-distance waypoint at the loose-drop position. The actual visual is rendered
    // client-side by GraveHologramRenderer reading ClientLooseDropCache; this entity exists purely
    // to drive vanilla entity-tracking sync so the client can capture the marker on first sight.
    private static void spawnLooseDropMarker(Level level, BlockPos pos, ServerPlayer player,
                                             long gameTime, LooseDropMarkerEntity.DropKind kind) {
        if (!PGConfig.COMMON.looseDropMarkerEnabled.get()) return;
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        LooseDropMarkerEntity e = LooseDropMarkerEntity.create(
            level, center, player.getUUID(),
            player.getGameProfile().getName(), gameTime, kind
        );
        boolean added = level.addFreshEntity(e);
        PGLog.debug(PGLog.CASCADE, "{} loose-drop marker addFreshEntity={} kind={} pos={}",
            player.getGameProfile().getName(), added, kind, pos);
    }
}
