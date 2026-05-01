package com.risen.perfectgraves.placement;

import com.risen.perfectgraves.claims.ClaimRegistry;
import com.risen.perfectgraves.config.PGConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ShellSearch {

    public record Result(List<BlockPos> candidates, Optional<BlockPos> safeDrop) {}

    private ShellSearch() {}

    // Shell-expansion search.
    // Iterates cube-shell surfaces of increasing radius from `start`, ordering positions
    // by ascending |dy| so near-Y spots come first. Collects up to `topN` valid grave
    // candidates (respecting claims via `claimCtx`) and the first safe-drop candidate.
    // Respects `budgetNanos` as a wall-clock cap and skips unloaded chunks silently —
    // never force-loads (Critical Invariant). Safe-drop candidates are not claim-checked:
    // they're an emergency fallback and claim integrity matters less than item preservation.
    public static Result search(Level level,
                                @Nullable Player player,
                                BlockPos start,
                                int maxRadius,
                                int topN,
                                long budgetNanos,
                                ClaimRegistry.SearchContext claimCtx) {
        long deadline = System.nanoTime() + budgetNanos;
        List<BlockPos> candidates = new ArrayList<>(topN);
        BlockPos bestDrop = null;

        int cx = start.getX(), cy = start.getY(), cz = start.getZ();

        outer:
        for (int r = 0; r <= maxRadius; r++) {
            for (int ady = 0; ady <= r; ady++) {
                int[] signs = (ady == 0) ? new int[]{0} : new int[]{-1, 1};
                for (int ys : signs) {
                    int y = cy + ys * ady;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            int adx = Math.abs(dx), adz = Math.abs(dz);
                            if (Math.max(Math.max(adx, ady), adz) != r) continue;

                            BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                            if (!level.isLoaded(pos)) continue;

                            if (candidates.size() < topN
                                && isValidGraveSpot(level, pos)
                                && claimCtx.canPlace(level, pos, player)) {
                                candidates.add(pos);
                                if (candidates.size() >= topN) break outer;
                            }
                            if (bestDrop == null && isSafeDropSpot(level, pos)) {
                                bestDrop = pos;
                            }
                        }
                    }
                }
            }
            if (System.nanoTime() > deadline) break;
        }

        return new Result(List.copyOf(candidates), Optional.ofNullable(bestDrop));
    }

    private static boolean isValidGraveSpot(Level level, BlockPos pos) {
        // Vanilla regenerates the End spawn platform on every entry — anything we place in that
        // box would be wiped (items lost) the next time the owner portals back. Reject early.
        if (DimensionOverrides.isInEndSpawnArea(level.dimension(), pos)) return false;

        // Don't place graves on the Nether bedrock ceiling or other inaccessible spaces above
        // the dimension's logical height (the highest Y reachable by normal gameplay).
        int maxY = level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1;
        if (pos.getY() > maxY) return false;

        BlockState state = level.getBlockState(pos);
        boolean replaceable = isReplaceable(state);
        // Water cells are also valid candidates when waterlogged graves are enabled — the
        // grave will be placed waterlogged so the water column is preserved (handled in
        // PlacementCascade.placeGrave). The sturdy-below check below still applies, so a
        // mid-ocean cell whose neighbor below is water is rejected — graves never float.
        boolean waterlog = PGConfig.COMMON.allowWaterloggedGraves.get()
            && level.getFluidState(pos).is(FluidTags.WATER);
        if (!replaceable && !waterlog) return false;
        BlockState below = level.getBlockState(pos.below());
        return below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private static boolean isSafeDropSpot(Level level, BlockPos pos) {
        if (DimensionOverrides.isInEndSpawnArea(level.dimension(), pos)) return false;

        // Don't drop items on the Nether bedrock ceiling or other inaccessible spaces above
        // the dimension's logical height.
        int maxY = level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1;
        if (pos.getY() > maxY) return false;

        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) return false;
        BlockState below = level.getBlockState(pos.below());
        if (!below.isFaceSturdy(level, pos.below(), Direction.UP)) return false;

        if (PGConfig.COMMON.safeDropAvoidVoid.get()
            && pos.getY() < level.getMinBuildHeight() + 5) {
            return false;
        }
        if (PGConfig.COMMON.safeDropAvoidLava.get()) {
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (level.isLoaded(n) && level.getFluidState(n).is(FluidTags.LAVA)) return false;
            }
        }
        return true;
    }

    private static boolean isReplaceable(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) return false;
        String idStr = id.toString();
        for (String entry : PGConfig.COMMON.replaceableBlocks.get()) {
            if (idStr.equals(entry)) return true;
        }
        return false;
    }
}
