package com.risen.perfectgraves.claims.integrations;

import com.risen.perfectgraves.claims.ClaimProvider;
import io.github.flemmli97.flan.api.ClaimHandler;
import io.github.flemmli97.flan.api.permission.BuiltinPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

public final class FlanProvider implements ClaimProvider {

    private static final String MOD_ID = "flan";

    @Override
    public String getModId() { return MOD_ID; }

    @Override
    public boolean isLoaded() { return ModList.get().isLoaded(MOD_ID); }

    @Override
    public boolean canPlaceGrave(Level level, BlockPos pos, @Nullable Player player) {
        if (!(level instanceof ServerLevel)) return true;
        // Flan's canInteract requires a ServerPlayer; without one we can't determine claim state,
        // so fall through as permissive (consistent with our error-handling policy — a chunk
        // without a known subject is treated as unclaimed).
        if (!(player instanceof ServerPlayer sp)) return true;

        return ClaimHandler.canInteract(sp, pos, BuiltinPermission.PLACE);
    }
}
