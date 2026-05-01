package com.risen.perfectgraves.grave;

import com.risen.perfectgraves.accessories.ExtendedInventoryRegistry;
import com.risen.perfectgraves.registry.PGBlocks;
import com.risen.perfectgraves.util.NbtKeys;
import com.risen.perfectgraves.util.PGLog;
import com.risen.perfectgraves.virtual.VirtualGrave;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.DiggerItem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Quick pickup & equip. Sort order:
//   1. Armor → matching armor slots (if empty)
//   2. Shield → offhand (if empty)
//   3. First weapon/tool → main hand (if empty)
//   4. Remaining → first-fit in main inventory
//   5. XP → awarded directly to the player (no orb spawned — pickup action implies owner
//      presence, so there's no need for the despawn-safe entity dance).
// If a target slot is already occupied (player re-equipped after death), the item falls through
// to the main-inventory bucket rather than displacing what the player chose.
public final class QuickPickup {

    private QuickPickup() {}

    public static void perform(ServerPlayer player, ServerLevel level, BlockPos pos, GraveBlockEntity be) {
        List<ItemStack> pool = collectItems(be);

        distributePool(player, pool);

        int xp = be.getXp();
        if (xp > 0) {
            player.giveExperiencePoints(xp);
        }

        be.clearContent();
        if (level.getBlockState(pos).is(PGBlocks.GRAVE.get())) {
            level.removeBlock(pos, false);
        }
        player.inventoryMenu.broadcastChanges();

        PGLog.info(PGLog.GRAVE, "{} quick-pickup @ {}: {} items, {} xp",
            player.getGameProfile().getName(), pos, pool.size(), xp);
    }

    // Called from `/grave restore` and the VirtualGraveMenu click handler. Same sort order as
    // physical-grave pickup; XP awarded directly (see perform() comment).
    public static void restoreVirtual(ServerPlayer player, VirtualGrave vg) {
        List<ItemStack> pool = new ArrayList<>(vg.items().size());
        for (ItemStack s : vg.items()) {
            if (!s.isEmpty()) pool.add(s.copy());
        }

        distributePool(player, pool);

        if (vg.xp() > 0) {
            player.giveExperiencePoints(vg.xp());
        }
        player.inventoryMenu.broadcastChanges();

        PGLog.info(PGLog.VGRAVE, "{} restored grave {}: {} items, {} xp",
            player.getGameProfile().getName(), vg.id(), vg.items().size(), vg.xp());
    }

    // Public entry point for soulbound restore on respawn/reconnect. Copies the inputs so the
    // caller's list isn't mutated (items may come from a shared cache).
    public static void restoreToPlayer(ServerPlayer player, List<ItemStack> items) {
        List<ItemStack> pool = new ArrayList<>(items.size());
        for (ItemStack s : items) {
            if (!s.isEmpty()) pool.add(s.copy());
        }
        distributePool(player, pool);
        player.inventoryMenu.broadcastChanges();
    }

    private static void distributePool(ServerPlayer player, List<ItemStack> pool) {
        equipAccessories(player, pool);
        equipArmor(player, pool);
        equipShield(player, pool);
        equipMainHandWeapon(player, pool);
        spillRest(player, pool);
    }

    // Items tagged with perfectgraves:curios_origin route back to their death-time accessory
    // slot if still empty. If not empty (player re-equipped), the item falls through to main
    // inventory. Tag is cleaned before equipping so it doesn't linger.
    private static void equipAccessories(ServerPlayer player, List<ItemStack> pool) {
        for (Iterator<ItemStack> it = pool.iterator(); it.hasNext(); ) {
            ItemStack stack = it.next();
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains(NbtKeys.CURIOS_ORIGIN)) continue;
            String slotId = tag.getString(NbtKeys.CURIOS_ORIGIN);

            // Clean the tag BEFORE attempting equip so the item is pristine if equipped,
            // and the grave/inventory fallback doesn't carry the phantom tag forever.
            tag.remove(NbtKeys.CURIOS_ORIGIN);
            if (tag.isEmpty()) stack.setTag(null);

            if (ExtendedInventoryRegistry.tryEquip(player, slotId, stack)) {
                it.remove();
            }
        }
    }

    private static List<ItemStack> collectItems(GraveBlockEntity be) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < be.getContainerSize(); i++) {
            ItemStack s = be.getItem(i);
            if (!s.isEmpty()) out.add(s.copy());
            be.setItem(i, ItemStack.EMPTY);
        }
        return out;
    }

    private static void equipArmor(ServerPlayer player, List<ItemStack> pool) {
        for (Iterator<ItemStack> it = pool.iterator(); it.hasNext(); ) {
            ItemStack stack = it.next();
            EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);
            if (slot.getType() != EquipmentSlot.Type.ARMOR) continue;
            if (!player.getItemBySlot(slot).isEmpty()) continue;
            player.setItemSlot(slot, stack);
            it.remove();
        }
    }

    private static void equipShield(ServerPlayer player, List<ItemStack> pool) {
        if (!player.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) return;
        for (Iterator<ItemStack> it = pool.iterator(); it.hasNext(); ) {
            ItemStack stack = it.next();
            if (stack.getItem() instanceof ShieldItem) {
                player.setItemSlot(EquipmentSlot.OFFHAND, stack);
                it.remove();
                return;
            }
        }
    }

    private static void equipMainHandWeapon(ServerPlayer player, List<ItemStack> pool) {
        int selected = player.getInventory().selected;
        if (!player.getInventory().items.get(selected).isEmpty()) return;
        for (Iterator<ItemStack> it = pool.iterator(); it.hasNext(); ) {
            ItemStack stack = it.next();
            if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof DiggerItem) {
                player.getInventory().items.set(selected, stack);
                it.remove();
                return;
            }
        }
    }

    private static void spillRest(ServerPlayer player, List<ItemStack> pool) {
        // Vanilla Inventory.add() has a creative-mode quirk: when player.abilities.instabuild
        // is true and the inventory is full, it silently sets stack.count=0 and returns true
        // — which would make our drop-on-overflow branch never fire and grave-restored items
        // disappear forever. Temporarily clear instabuild so add() returns false on a full
        // inventory and we drop leftovers to the ground, matching survival behavior. Restored
        // unconditionally in finally; no client packets are pushed by direct field writes.
        Abilities abilities = player.getAbilities();
        boolean wasInstabuild = abilities.instabuild;
        if (wasInstabuild) abilities.instabuild = false;
        try {
            for (ItemStack stack : pool) {
                if (stack.isEmpty()) continue;
                if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                    player.drop(stack, false);
                }
            }
        } finally {
            if (wasInstabuild) abilities.instabuild = true;
        }
    }
}
