package com.risen.perfectgraves.soulbound;

import com.risen.perfectgraves.config.PGConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Detect items tagged as "soulbound" by supported mods and divert them from the drop pool
// BEFORE the cascade runs. A match means the item never reaches the grave — it's parked in
// SoulboundTracker and restored to the player's inventory on respawn.
//
// Two match mechanisms, both config-driven:
//   1. Enchantment ID match — item has any enchantment whose registry ID appears in config
//   2. Root NBT boolean flag — item's root NBT contains a true-valued boolean with the configured key
public final class SoulboundFilter {

    private SoulboundFilter() {}

    // Mutates `pool`: removes soulbound items and returns them as a new list. Caller is
    // responsible for parking the returned list (typically via SoulboundTracker.store).
    public static List<ItemStack> split(List<ItemStack> pool) {
        List<ItemStack> soulbound = new ArrayList<>();
        Iterator<ItemStack> it = pool.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (isSoulbound(stack)) {
                soulbound.add(stack);
                it.remove();
            }
        }
        return soulbound;
    }

    public static boolean isSoulbound(ItemStack stack) {
        if (stack.isEmpty()) return false;

        List<? extends String> enchantMarkers = PGConfig.COMMON.soulboundEnchantments.get();
        if (!enchantMarkers.isEmpty()) {
            Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
            for (Enchantment e : enchants.keySet()) {
                ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(e);
                if (id == null) continue;
                String idStr = id.toString();
                for (String marker : enchantMarkers) {
                    if (idStr.equals(marker)) return true;
                }
            }
        }

        List<? extends String> nbtMarkers = PGConfig.COMMON.soulboundNbtTags.get();
        if (!nbtMarkers.isEmpty()) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                for (String marker : nbtMarkers) {
                    if (tag.contains(marker) && tag.getBoolean(marker)) return true;
                }
            }
        }

        return false;
    }
}
