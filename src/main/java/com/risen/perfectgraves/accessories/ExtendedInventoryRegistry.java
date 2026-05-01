package com.risen.perfectgraves.accessories;

import com.risen.perfectgraves.util.PGLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ExtendedInventoryRegistry {

    private static final List<ExtendedInventoryProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ExtendedInventoryRegistry() {}

    public static void register(ExtendedInventoryProvider p) {
        PROVIDERS.add(p);
        PGLog.info(PGLog.ACCESSORIES, "registered provider: {}", p.getModId());
    }

    // Merged snapshot across all loaded providers. If two providers claim the same slot id,
    // last-writer wins — acceptable because in practice only one accessory mod is loaded at a time
    // on Forge 1.20.1 (Accessories has no Forge port; Curios is the sole provider).
    public static Map<String, ItemStack> snapshotAll(ServerPlayer player) {
        Map<String, ItemStack> merged = new HashMap<>();
        for (ExtendedInventoryProvider p : PROVIDERS) {
            if (!p.isLoaded()) continue;
            try {
                merged.putAll(p.snapshotAt(player));
            } catch (Throwable t) {
                PGLog.warn(PGLog.ACCESSORIES, "{} threw in snapshotAt; skipping", p.getModId(), t);
            }
        }
        return merged;
    }

    public static Optional<String> identify(ItemStack stack, Map<String, ItemStack> snapshot) {
        for (ExtendedInventoryProvider p : PROVIDERS) {
            if (!p.isLoaded()) continue;
            try {
                Optional<String> origin = p.identifyOrigin(stack, snapshot);
                if (origin.isPresent()) return origin;
            } catch (Throwable t) {
                PGLog.warn(PGLog.ACCESSORIES, "{} threw in identifyOrigin; skipping", p.getModId(), t);
            }
        }
        return Optional.empty();
    }

    public static boolean tryEquip(ServerPlayer player, String slotId, ItemStack stack) {
        for (ExtendedInventoryProvider p : PROVIDERS) {
            if (!p.isLoaded()) continue;
            try {
                if (p.tryEquip(player, slotId, stack)) return true;
            } catch (Throwable t) {
                PGLog.warn(PGLog.ACCESSORIES, "{} threw in tryEquip; skipping", p.getModId(), t);
            }
        }
        return false;
    }
}
