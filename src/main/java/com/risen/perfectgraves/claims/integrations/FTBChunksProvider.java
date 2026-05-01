package com.risen.perfectgraves.claims.integrations;

import com.risen.perfectgraves.claims.ClaimProvider;
import com.risen.perfectgraves.config.PGConfig;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ClaimedChunkManager;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

public final class FTBChunksProvider implements ClaimProvider {

    private static final String MOD_ID = "ftbchunks";

    @Override
    public String getModId() { return MOD_ID; }

    @Override
    public boolean isLoaded() { return ModList.get().isLoaded(MOD_ID); }

    @Override
    public boolean canPlaceGrave(Level level, BlockPos pos, @Nullable Player player) {
        if (!(level instanceof ServerLevel serverLevel)) return true;

        FTBChunksAPI.API api = FTBChunksAPI.api();
        if (!api.isManagerLoaded()) return true;
        ClaimedChunkManager mgr = api.getManager();

        ClaimedChunk claim = mgr.getChunk(new ChunkDimPos(serverLevel, pos));
        if (claim == null) return true;                          // unclaimed

        if (player == null) return false;                        // unknown subject — conservative

        boolean isOwnClaim = claim.getTeamData().isTeamMember(player.getUUID());
        if (!isOwnClaim) return false;                           // someone else's claim
        return PGConfig.COMMON.allowOwnClaims.get();             // own claim gated by config
    }
}
