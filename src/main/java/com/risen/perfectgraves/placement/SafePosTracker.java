package com.risen.perfectgraves.placement;

import com.risen.perfectgraves.PerfectGraves;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// In-memory last-known-safe-position tracker (no disk persistence — see CLAUDE.md Critical
// Invariants). Keyed by UUID, never Player reference — Player objects are recreated on respawn
// / dim change in some server impls, which would leak entries in a WeakHashMap<Player, ...>.
// ConcurrentHashMap<UUID, SafePosData> with explicit clearing on logout + dim change avoids that.
@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID)
public final class SafePosTracker {

    private static final Map<UUID, SafePosData> CACHE = new ConcurrentHashMap<>();

    private SafePosTracker() {}

    public static Optional<SafePosData> get(UUID uuid) {
        return Optional.ofNullable(CACHE.get(uuid));
    }

    public static void clear(UUID uuid) {
        CACHE.remove(uuid);
    }

    public static void clearAll() {
        CACHE.clear();
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer sp)) return;
        if (sp.tickCount % 20 != 0) return;                         // 1 Hz per spec §6.5
        if (!sp.onGround()) return;

        Level level = sp.level();
        if (sp.getY() < level.getMinBuildHeight() + 5) return;       // skip near-void

        BlockPos pos = sp.blockPosition();
        BlockState below = level.getBlockState(pos.below());
        if (below.isAir()) return;                                   // no support below
        if (below.getFluidState().is(FluidTags.LAVA)) return;        // lava below — not safe

        CACHE.put(sp.getUUID(), new SafePosData(level.dimension(), pos, level.getGameTime()));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity().getUUID());
    }

    // Dimension change invalidates the snapshot — a pos from the overworld is useless in the nether.
    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        clear(event.getEntity().getUUID());
    }

    // Test hook: exposes the active ResourceKey type so callers elsewhere don't have to import Level.
    @SuppressWarnings("unused")
    public static void forceUpdate(UUID uuid, ResourceKey<Level> dim, BlockPos pos, long gameTime) {
        CACHE.put(uuid, new SafePosData(dim, pos, gameTime));
    }
}
