package com.risen.perfectgraves.placement;

import com.risen.perfectgraves.claims.ClaimRegistry;
import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.death.DeathContext;
import com.risen.perfectgraves.util.PGLog;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlatformBuilder {

    public record Result(boolean placed, boolean claimBlocked) {}

    private PlatformBuilder() {}

    // Builds a 3x3 platform one block below `center` (so the grave placed AT `center` rests on it).
    // Critical Invariants:
    //   - ALL 9 blocks must pass ClaimRegistry.canPlace, not just the center.
    //   - Never overwrite blocks listed in `unreplaceableBlocks` (bedrock, barriers, etc.).
    // Returns Result.placed=true on success. On failure, Result.claimBlocked distinguishes the
    // claim-denied case so the cascade can route straight to virtual grave rather than retrying.
    public static Result tryPlace(Level level,
                                  BlockPos center,
                                  Player player,
                                  ClaimRegistry.SearchContext claimCtx,
                                  DeathContext.DeathType deathType) {
        if (!PGConfig.COMMON.allowPlatformGeneration.get()) {
            return new Result(false, false);
        }

        List<BlockPos> platform = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                platform.add(center.offset(dx, -1, dz));
            }
        }

        // Full 9-block claim check.
        for (BlockPos p : platform) {
            if (!claimCtx.canPlace(level, p, player)) {
                PGLog.warn(PGLog.PLATFORM, "claim-blocked at {} (part of platform around {})", p, center);
                return new Result(false, true);
            }
        }

        // Unreplaceable check — never clobber bedrock, barriers, end portal frames, etc.
        Set<String> unreplaceable = new HashSet<>(PGConfig.COMMON.unreplaceableBlocks.get());
        for (BlockPos p : platform) {
            BlockState existing = level.getBlockState(p);
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(existing.getBlock());
            if (id != null && unreplaceable.contains(id.toString())) {
                PGLog.warn(PGLog.PLATFORM, "unreplaceable {} at {}; aborting platform for {}", id, p, center);
                return new Result(false, false);
            }
        }

        BlockState platformState = resolvePlatformBlock(level, deathType);
        for (BlockPos p : platform) {
            level.setBlock(p, platformState, Block.UPDATE_ALL);
        }
        PGLog.info(PGLog.PLATFORM, "built 3x3 at y={} under {} using {}",
            center.getY() - 1, center, ForgeRegistries.BLOCKS.getKey(platformState.getBlock()));
        return new Result(true, false);
    }

    private static BlockState resolvePlatformBlock(Level level, DeathContext.DeathType type) {
        String id = DimensionOverrides.platformBlockId(level, type);
        @SuppressWarnings("removal")
        ResourceLocation rl = new ResourceLocation(id);
        Block block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null || block == Blocks.AIR) {
            PGLog.warn(PGLog.PLATFORM, "configured block {} not found; falling back to obsidian", id);
            return Blocks.OBSIDIAN.defaultBlockState();
        }
        return block.defaultBlockState();
    }
}
