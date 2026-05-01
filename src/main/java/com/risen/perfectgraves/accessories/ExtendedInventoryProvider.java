package com.risen.perfectgraves.accessories;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

// Interface for accessory mods (Curios today, NeoForge Accessories someday). Deliberately does
// NOT expose extractAndClear: those mods' own LivingDropsEvent listeners already contribute
// worn items to event.getDrops(). Our flow is:
//   1. LivingDeathEvent @ HIGHEST: snapshotAt(player) — record slot -> stack mapping.
//   2. LivingDropsEvent @ LOWEST: identifyOrigin(stack, snapshot) — tag matching drops with
//      the slot id via NBT so the tag survives into grave NBT.
//   3. On pickup: tryEquip(player, slotId, stack) — route back to the accessory slot if empty.
public interface ExtendedInventoryProvider {

    String getModId();

    boolean isLoaded();

    // Snapshot of worn items, keyed by `<slotType>:<index>` (e.g., "ring:0"). Never null.
    Map<String, ItemStack> snapshotAt(ServerPlayer player);

    // If `stack` matches an entry in `snapshot` (same item + NBT), return that entry's key.
    // Caller uses the key to tag the grave item so tryEquip can route it back later.
    Optional<String> identifyOrigin(ItemStack stack, Map<String, ItemStack> snapshot);

    // Attempt to equip `stack` into the accessory slot identified by `slotId`. Returns true
    // on success, false if the slot doesn't exist, is occupied, or the stack isn't valid there.
    // Caller falls back to main inventory on false.
    boolean tryEquip(ServerPlayer player, String slotId, ItemStack stack);
}
