package com.risen.perfectgraves.soulbound;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.grave.QuickPickup;
import com.risen.perfectgraves.util.PGLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// In-memory buffer for items filtered out by SoulboundFilter. Restored on Clone (normal respawn
// flow) or PlayerLoggedInEvent (fallback for disconnect-during-death-screen case). Both handlers
// drain via `take`, so whichever fires first wins; the other finds an empty list — idempotent.
//
// Plan Critical Invariant: in-memory only. Items here are lost if the server restarts between
// death and restore. Acceptable per spec §7.3 and the "no caps, no disk I/O" invariant.
@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID)
public final class SoulboundTracker {

    private static final Map<UUID, List<ItemStack>> CACHE = new ConcurrentHashMap<>();

    private SoulboundTracker() {}

    public static void store(UUID uuid, List<ItemStack> items) {
        if (items.isEmpty()) return;
        CACHE.compute(uuid, (k, existing) -> {
            List<ItemStack> merged = existing != null ? existing : new ArrayList<>();
            for (ItemStack s : items) if (!s.isEmpty()) merged.add(s);
            return merged;
        });
    }

    public static List<ItemStack> take(UUID uuid) {
        List<ItemStack> items = CACHE.remove(uuid);
        return items != null ? items : List.of();
    }

    public static void clearAll() {
        CACHE.clear();
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        if (!(event.getEntity() instanceof ServerPlayer newPlayer)) return;
        List<ItemStack> items = take(newPlayer.getUUID());
        if (items.isEmpty()) return;
        QuickPickup.restoreToPlayer(newPlayer, items);
        PGLog.info(PGLog.SOULBOUND, "{} restored {} items on respawn",
            newPlayer.getGameProfile().getName(), items.size());
    }

    // Fallback: if the player disconnected from the death screen without clicking Respawn,
    // Clone hasn't fired yet. Restore on reconnect instead.
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        List<ItemStack> items = take(player.getUUID());
        if (items.isEmpty()) return;
        QuickPickup.restoreToPlayer(player, items);
        PGLog.info(PGLog.SOULBOUND, "{} restored {} items on reconnect",
            player.getGameProfile().getName(), items.size());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
    }

    // Post-respawn armor re-equip. Third-party soulbound enchantments (EnderIO,
    // Ars Elemental, etc.) typically prevent their items from ever reaching LivingDropsEvent
    // — they hook earlier and restore the item to the player's main inventory on respawn,
    // without slot-routing. The result: a player who soulbound-restored a chestplate ends up
    // with it sitting in the main inventory grid.
    //
    // We try twice: once at LOWEST priority on Clone (most mods restore here), and once on
    // PlayerRespawnEvent (some mods restore later via this event or via packets that arrive
    // after Clone). Both handlers scan the main inventory for armor pieces and move them into
    // their matching armor slots when those slots are empty. Hotbar slots (0-8) are left
    // alone — players sometimes intentionally hotbar armor pieces. Doesn't touch our own
    // soulbound items because by the time we get here our equipArmor has already filled the
    // armor slots, so the empty-slot check rejects double-equip attempts.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCloneAutoEquipArmor(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        autoEquipArmorFromMainInventory(player, "Clone");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRespawnAutoEquipArmor(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return; // end-credits exit, not death respawn
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        autoEquipArmorFromMainInventory(player, "Respawn");
    }

    // Scans the entire main inventory (including hotbar — third-party soulbound restorers like
    // Mystical Agriculture's Awakened Supremium drop items straight into hotbar slots) and
    // moves armor pieces into matching empty armor slots. Safe to run twice (Clone + Respawn);
    // the second pass finds nothing because slots are already filled.
    private static void autoEquipArmorFromMainInventory(ServerPlayer player, String source) {
        Inventory inv = player.getInventory();
        boolean changed = false;
        int moved = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.isEmpty()) continue;
            EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);
            if (slot.getType() != EquipmentSlot.Type.ARMOR) continue;
            if (!player.getItemBySlot(slot).isEmpty()) continue;
            player.setItemSlot(slot, stack);
            inv.items.set(i, ItemStack.EMPTY);
            changed = true;
            moved++;
        }
        if (changed) {
            player.inventoryMenu.broadcastChanges();
            PGLog.debug(PGLog.SOULBOUND, "{} auto-equipped {} armor piece(s) on {}",
                player.getGameProfile().getName(), moved, source);
        }
    }
}
