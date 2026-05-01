package com.risen.perfectgraves.claims.integrations;

import com.risen.perfectgraves.claims.ClaimProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

public final class OPACProvider implements ClaimProvider {

    private static final String MOD_ID = "openpartiesandclaims";

    @Override
    public String getModId() { return MOD_ID; }

    @Override
    public boolean isLoaded() { return ModList.get().isLoaded(MOD_ID); }

    @Override
    public boolean canPlaceGrave(Level level, BlockPos pos, @Nullable Player player) {
        if (!(level instanceof ServerLevel sl)) return true;
        MinecraftServer server = sl.getServer();
        if (server == null) return true;

        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        if (api == null) return true;
        IChunkProtectionAPI protection = api.getChunkProtection();
        if (protection == null) return true;

        // Returns true when the placement IS protected against (i.e., denied).
        // We invert: our contract returns true when placement is ALLOWED.
        boolean denied = protection.onEntityPlaceBlock(player, sl, pos);
        return !denied;
    }
}
