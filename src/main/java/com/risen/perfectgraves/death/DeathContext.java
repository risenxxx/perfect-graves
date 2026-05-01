package com.risen.perfectgraves.death;

import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.placement.DimensionOverrides;
import com.risen.perfectgraves.placement.SafePosData;
import com.risen.perfectgraves.util.PGLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public record DeathContext(
    DeathType type,
    ResourceKey<Level> dimension,
    BlockPos deathPos,
    BlockPos correctedPos,
    boolean requiresPlatform,
    boolean isDeathLoop
) {
    public enum DeathType { NORMAL, LAVA, VOID, SUFFOCATION }

    public static DeathContext simple(ResourceKey<Level> dim, BlockPos pos) {
        return new DeathContext(DeathType.NORMAL, dim, pos, pos, false, false);
    }

    // Detects lava/void/suffocation, produces a corrected start position and requiresPlatform
    // flag. Normal deaths go through unchanged — shell search handles them.
    // `isDeathLoop` is detected separately (see DeathLoopDetector) and passed through here so the
    // cascade can honor it without re-running the check.
    public static DeathContext classify(Level level, Player player, Optional<SafePosData> safePos, boolean isDeathLoop) {
        ResourceKey<Level> dim = level.dimension();
        BlockPos deathPos = player.blockPosition();

        DeathContext ctx;
        if (deathPos.getY() < level.getMinBuildHeight()) {
            ctx = classifyVoid(level, dim, deathPos, safePos, isDeathLoop);
        } else if (isLavaDeath(level, deathPos)) {
            ctx = classifyLava(level, dim, deathPos, isDeathLoop);
        } else if (isSuffocationDeath(level, deathPos)) {
            ctx = classifySuffocation(level, dim, deathPos, isDeathLoop);
        } else {
            ctx = new DeathContext(DeathType.NORMAL, dim, deathPos, deathPos, false, isDeathLoop);
        }

        PGLog.debug(PGLog.DEATH, "{} classify: type={} corrected={} platform={} loop={} (safePos={})",
            player.getGameProfile().getName(), ctx.type(), ctx.correctedPos(),
            ctx.requiresPlatform(), ctx.isDeathLoop(),
            safePos.map(s -> s.pos() + "@" + s.dimension().location()).orElse("<none>"));

        return ctx;
    }

    private static DeathContext classifyVoid(Level level, ResourceKey<Level> dim, BlockPos deathPos,
                                             Optional<SafePosData> safePos, boolean isDeathLoop) {
        // Skip safePos if it points into vanilla's End-spawn regen box — placing a grave there
        // works briefly but vanilla wipes the area on every End re-entry, so the items would
        // disappear the next time the owner portals back. Falling through to platform mode puts
        // the grave at voidGravePlatformY (default 64 in The End), which is safely above the
        // regen zone.
        boolean safePosInEndSpawn = safePos.isPresent()
            && safePos.get().dimension().equals(dim)
            && DimensionOverrides.isInEndSpawnArea(dim, safePos.get().pos());

        // Validate that the recorded safePos still has solid ground under it. Player can break
        // the block they were standing on between the time SafePosTracker captured the position
        // and the time they actually fall to their death — when that happens, "safe pos" points
        // into open void. Trusting it would skip platform mode (requiresPlatform=false), the
        // shell search would find zero candidates (no sturdy block below), and the cascade
        // would fall through to vanilla-drop into the void. Validate here and treat broken
        // safePos as no-safePos so platform mode kicks in.
        boolean safePosStillSafe = safePos.isPresent()
            && safePos.get().dimension().equals(dim)
            && hasSturdyGround(level, safePos.get().pos());

        if (safePos.isPresent() && safePos.get().dimension().equals(dim)
                && !safePosInEndSpawn && safePosStillSafe) {
            return new DeathContext(DeathType.VOID, dim, deathPos, safePos.get().pos(), false, isDeathLoop);
        }
        int platformY = DimensionOverrides.voidGravePlatformY(level);
        BlockPos target = new BlockPos(deathPos.getX(), platformY, deathPos.getZ());
        return new DeathContext(DeathType.VOID, dim, deathPos, target, true, isDeathLoop);
    }

    // True if the block at pos.below() is solid enough to stand on — a sturdy upward face. Used
    // to verify that a recorded safePos hasn't been invalidated by the player breaking the
    // block they were standing on. Unloaded chunks return false (defensive: we can't validate).
    private static boolean hasSturdyGround(Level level, BlockPos pos) {
        BlockPos below = pos.below();
        if (!level.isLoaded(below)) return false;
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private static DeathContext classifyLava(Level level, ResourceKey<Level> dim, BlockPos deathPos, boolean isDeathLoop) {
        int maxCorrection = PGConfig.COMMON.maxVerticalCorrection.get();

        // Scan upward for first air cell with a sturdy face below it.
        for (int dy = 0; dy <= maxCorrection; dy++) {
            BlockPos test = deathPos.above(dy);
            if (!level.getBlockState(test).isAir()) continue;
            BlockState below = level.getBlockState(test.below());
            if (below.isFaceSturdy(level, test.below(), Direction.UP)) {
                return new DeathContext(DeathType.LAVA, dim, deathPos, test, false, isDeathLoop);
            }
        }

        // No natural air-over-solid found within budget — requires a platform at the lava surface.
        BlockPos lavaSurface = findLavaSurface(level, deathPos, maxCorrection);
        return new DeathContext(DeathType.LAVA, dim, deathPos, lavaSurface, true, isDeathLoop);
    }

    // Mirrors classifyLava: scan from the buried position for the first air cell that has a
    // sturdy face below it. Without this, shell search starts inside a wall and the nearest
    // valid candidates are typically cave air pockets — graves end up randomly inside mountains.
    //
    // Up-scan capped at the dimension's logical height (the highest Y entities can normally
    // reach). Above that is bedrock-ceiling air the player can't get to — promoting the grave
    // there is just item loss with extra steps. Logical height is 128 in the Nether and matches
    // build height in dimensions without a ceiling, so this reads correctly everywhere.
    //
    // If up-scan fails, also try down-scan: a player buried directly under the Nether bedrock
    // ceiling has open Nether *below* their feet, not above. Down-scan is bounded by
    // minBuildHeight (one cell above the void abyss).
    //
    // If both directions fail, fall back to NORMAL — shell search may still find a cave nearby,
    // and drilling up/down through someone's mountain is worse UX than letting that play out.
    private static DeathContext classifySuffocation(Level level, ResourceKey<Level> dim, BlockPos deathPos, boolean isDeathLoop) {
        int maxCorrection = PGConfig.COMMON.maxVerticalCorrection.get();
        int minBuildHeight = level.getMinBuildHeight();
        int logicalHeight = level.dimensionType().logicalHeight();
        int maxY = minBuildHeight + logicalHeight - 1;

        PGLog.debug(PGLog.DEATH, "classifySuffocation: deathPos={} minBuild={} logicalHeight={} maxY={} dim={}",
            deathPos, minBuildHeight, logicalHeight, maxY, dim.location());

        // Up-scan: deathPos.above(1) .. deathPos.above(maxCorrection), inclusive of test.Y=maxY.
        for (int dy = 1; dy <= maxCorrection; dy++) {
            BlockPos test = deathPos.above(dy);
            if (test.getY() > maxY) {
                PGLog.debug(PGLog.DEATH, "  up-scan break at dy={} test.Y={} > maxY={}", dy, test.getY(), maxY);
                break;
            }
            if (!level.getBlockState(test).isAir()) continue;
            BlockState below = level.getBlockState(test.below());
            if (below.isFaceSturdy(level, test.below(), Direction.UP)) {
                PGLog.debug(PGLog.DEATH, "  up-scan surface at dy={} test.Y={}", dy, test.getY());
                return new DeathContext(DeathType.SUFFOCATION, dim, deathPos, test, false, isDeathLoop);
            }
        }

        // Down-scan: deathPos.below(1) .. deathPos.below(maxCorrection). Bounded by
        // minBuildHeight + 1 because we still need the cell *below* the candidate to be sturdy.
        for (int dy = 1; dy <= maxCorrection; dy++) {
            BlockPos test = deathPos.below(dy);
            if (test.getY() <= minBuildHeight) {
                PGLog.debug(PGLog.DEATH, "  down-scan break at dy={} test.Y={} <= minBuildHeight={}", dy, test.getY(), minBuildHeight);
                break;
            }
            if (!level.getBlockState(test).isAir()) continue;
            BlockState below = level.getBlockState(test.below());
            if (below.isFaceSturdy(level, test.below(), Direction.UP)) {
                PGLog.debug(PGLog.DEATH, "  down-scan surface at dy={} test.Y={}", dy, test.getY());
                return new DeathContext(DeathType.SUFFOCATION, dim, deathPos, test, false, isDeathLoop);
            }
        }

        PGLog.debug(PGLog.DEATH, "  no surface up or down, falling back to NORMAL");
        return new DeathContext(DeathType.NORMAL, dim, deathPos, deathPos, false, isDeathLoop);
    }

    // True if the player's body is embedded in a block that vanilla considers suffocating —
    // matches the same predicate vanilla uses for the in-wall damage source. Excludes fluids
    // (lava is handled separately; water/drowning isn't a "buried" case).
    private static boolean isSuffocationDeath(Level level, BlockPos deathPos) {
        if (level.getBlockState(deathPos).isSuffocating(level, deathPos)) return true;
        BlockPos head = deathPos.above();
        return level.getBlockState(head).isSuffocating(level, head);
    }

    private static boolean isLavaDeath(Level level, BlockPos deathPos) {
        if (level.getFluidState(deathPos).is(FluidTags.LAVA)) return true;
        // Lava within 3 blocks below = standing on a ledge above lava, counts as lava death.
        for (int dy = 1; dy <= 3; dy++) {
            if (level.getFluidState(deathPos.below(dy)).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private static BlockPos findLavaSurface(Level level, BlockPos deathPos, int maxScan) {
        BlockPos.MutableBlockPos cursor = deathPos.mutable();
        int maxY = level.getMaxBuildHeight();
        for (int i = 0; i < maxScan * 2; i++) {
            if (cursor.getY() >= maxY) break;
            if (!level.getFluidState(cursor).is(FluidTags.LAVA)) break;
            cursor.move(Direction.UP);
        }
        return cursor.immutable();
    }
}
