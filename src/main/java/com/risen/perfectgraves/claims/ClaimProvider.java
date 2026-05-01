package com.risen.perfectgraves.claims;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public interface ClaimProvider {

    String getModId();

    boolean isLoaded();

    // Returns true if a grave may be placed at (level, pos) by `player`.
    // `player` may be null if the caller can't resolve it (e.g., fallback paths); providers
    // should treat null as "unknown subject" and deny conservatively if in doubt.
    boolean canPlaceGrave(Level level, BlockPos pos, @Nullable Player player);
}
