package com.risen.perfectgraves.grave;

import com.risen.perfectgraves.PerfectGraves;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Hologram protection-timer alignment hook. The renderer uses two formulas: gameTime-extrapolation
// while the owner is online (smooth) vs. (total-elapsed) while offline (frozen). Switching between
// them at login/logout would otherwise expose up to ~10s of drift accumulated since the last
// periodic BE sync — the displayed remaining time visibly jumps. Pushing a sync the moment a
// grave-owner's presence changes shrinks that drift to a single tick (~50ms), eliminating the jump.
@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID)
public final class OwnerPresenceSync {

    private OwnerPresenceSync() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            GraveBlockEntity.syncForOwner(sp.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            GraveBlockEntity.syncForOwner(sp.getUUID());
        }
    }
}
