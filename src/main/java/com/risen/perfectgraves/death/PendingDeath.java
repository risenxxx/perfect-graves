package com.risen.perfectgraves.death;

import com.risen.perfectgraves.placement.SafePosData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

public record PendingDeath(
    DeathContext context,
    Optional<SafePosData> safePosSnapshot,
    Map<String, ItemStack> curiosSnapshot,
    long gameTime,
    @Nullable Component deathMessage,
    Optional<CascadeResult> cascadeResult
) {
    public PendingDeath withResult(CascadeResult result) {
        return new PendingDeath(context, safePosSnapshot, curiosSnapshot, gameTime, deathMessage, Optional.of(result));
    }
}
