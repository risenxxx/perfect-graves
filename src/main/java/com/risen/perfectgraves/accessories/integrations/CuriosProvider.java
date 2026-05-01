package com.risen.perfectgraves.accessories.integrations;

import com.risen.perfectgraves.accessories.ExtendedInventoryProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class CuriosProvider implements ExtendedInventoryProvider {

    private static final String MOD_ID = "curios";

    @Override
    public String getModId() { return MOD_ID; }

    @Override
    public boolean isLoaded() { return ModList.get().isLoaded(MOD_ID); }

    @Override
    public Map<String, ItemStack> snapshotAt(ServerPlayer player) {
        Map<String, ItemStack> out = new HashMap<>();
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (Map.Entry<String, ICurioStacksHandler> e : handler.getCurios().entrySet()) {
                String slotType = e.getKey();
                IDynamicStackHandler stacks = e.getValue().getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        out.put(slotType + ":" + i, stack.copy());
                    }
                }
            }
        });
        return out;
    }

    @Override
    public Optional<String> identifyOrigin(ItemStack stack, Map<String, ItemStack> snapshot) {
        if (stack.isEmpty() || snapshot.isEmpty()) return Optional.empty();
        for (Map.Entry<String, ItemStack> e : snapshot.entrySet()) {
            // Only match keys we produced (slot_type:index). Defends against foreign entries if
            // another provider ever shares the same snapshot map.
            if (!e.getKey().contains(":")) continue;
            if (ItemStack.isSameItemSameTags(e.getValue(), stack)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean tryEquip(ServerPlayer player, String slotId, ItemStack stack) {
        int colon = slotId.indexOf(':');
        if (colon <= 0) return false;
        String type = slotId.substring(0, colon);
        int index;
        try {
            index = Integer.parseInt(slotId.substring(colon + 1));
        } catch (NumberFormatException ignored) {
            return false;
        }

        return CuriosApi.getCuriosInventory(player).map(handler -> {
            Optional<ICurioStacksHandler> opt = handler.getStacksHandler(type);
            if (opt.isEmpty()) return false;
            ICurioStacksHandler sh = opt.get();
            if (index < 0 || index >= sh.getSlots()) return false;
            if (!sh.getStacks().getStackInSlot(index).isEmpty()) return false;  // occupied
            handler.setEquippedCurio(type, index, stack);
            return true;
        }).orElse(false);
    }
}
