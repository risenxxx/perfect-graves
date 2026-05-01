package com.risen.perfectgraves.grave;

import com.risen.perfectgraves.PerfectGraves;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID)
public final class GraveInteractionHandler {

    private GraveInteractionHandler() {}

    // HIGH priority so we run before default listeners and can short-circuit a break by
    // performing the quick-pickup before vanilla removes the block. Break-by-owner is an
    // alternate quick-pickup trigger alongside right-click.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof GraveBlockEntity be)) return;

        if (!be.canAccess(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(
                Component.translatable("perfectgraves.grave.protected"), true);
            return;
        }

        if (event.getPlayer() instanceof ServerPlayer sp && event.getLevel() instanceof ServerLevel sl) {
            // Cancel the event — QuickPickup removes the block itself after transferring contents
            // and spawning the SafeXPEntity. This avoids vanilla's "drop items from a now-empty BE"
            // path racing with our clear.
            event.setCanceled(true);
            QuickPickup.perform(sp, sl, event.getPos(), be);
        }
    }

    // Creative-mode left-click bypasses Block.getDestroyProgress (which is what gives non-owners
    // the bedrock feel in survival). Vanilla short-circuits to instabreak, the client predicts
    // the break, and the BlockEvent.BreakEvent cancellation only restores the block after a
    // visible flash. Cancelling LeftClickBlock fires before the action packet is sent, so the
    // click silently does nothing — no animation, no flash. Survival is intentionally untouched
    // here so getDestroyProgress=0 keeps producing the bedrock-like swing feedback.
    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getEntity().isCreative()) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof GraveBlockEntity be)) return;
        if (be.canAccess(event.getEntity())) return;
        event.setCanceled(true);
        event.setUseBlock(Event.Result.DENY);
    }
}
