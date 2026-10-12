package com.risen.perfectgraves.grave;

import com.risen.perfectgraves.registry.PGBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class GraveBlock extends BaseEntityBlock implements SimpleWaterloggedBlock {

    // Standard vanilla waterlog property — let the block coexist with a water source so a grave
    // placed underwater preserves the water column instead of replacing it. Implementing
    // SimpleWaterloggedBlock gives us canPlaceLiquid / placeLiquid / pickupBlock for free.
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    // Short visible stub — low enough to walk over, tall enough to right-click reliably.
    // The real visual (skull + hologram) comes from the BER in step 8.
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 8, 14);

    public GraveBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    // Schedule a water tick when neighbors update so adjacent water flow stays consistent
    // (matches what FenceBlock / StairBlock / SignBlock do). Without this the water around
    // the grave can occasionally show stale flow direction after placement.
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // When the grave block is replaced (broken / picked up), evict the client-side marker cache
    // entry so the marker stops rendering. Kept as a defensive hook only: in 1.20.1
    // LevelChunk.setBlockState calls onRemove on the server and goes straight to
    // removeBlockEntity on the client, so the client's eviction in practice comes from
    // GraveBlockEntity.setRemoved. Server-side execution never loads the client class because
    // the call is gated by isClientSide.
    @Override
    public void onRemove(BlockState pState, Level pLevel, BlockPos pPos, BlockState pNewState, boolean pIsMoving) {
        boolean blockChanged = !pState.is(pNewState.getBlock());

        if (pLevel.isClientSide && blockChanged) {
            com.risen.perfectgraves.client.ClientGraveCache.evictAt(pLevel.dimension(), pPos);
        }

        // Rescue net for removals that bypass every one of our own pickup paths. The block has
        // noLootTable() and its contents live only in the BE, so anything that simply swaps the
        // block for air destroys the items outright: the Ender Dragon's wall-breaker (removeBlock,
        // ignores blast resistance and only honours #minecraft:dragon_immune), a wither's block
        // smash (destroyBlock, same deal), modded block breakers and quarries, /setblock,
        // worldedit. None of those fire BlockEvent.BreakEvent.
        //
        // onRemove runs BEFORE super removes the BE, so the contents are still readable here, and
        // vanilla does not call it on chunk unload (only setRemoved), so it cannot fire
        // spuriously. All of our own paths — QuickPickup, the menu's drained-grave cleanup, the
        // self-destruct timer, the placeGrave rollback — empty the BE before removing the block,
        // so the rescue no-ops for them.
        //
        // pIsMoving means vanilla intends to recreate the BE elsewhere (piston). PushReaction.BLOCK
        // makes that unreachable for us, but honour the contract rather than risk duplicating.
        if (blockChanged && !pIsMoving && !pLevel.isClientSide
            && pLevel instanceof ServerLevel sl
            && sl.getBlockEntity(pPos) instanceof GraveBlockEntity be) {
            be.rescueFromExternalRemoval(sl, pPos);
        }

        super.onRemove(pState, pLevel, pPos, pNewState, pIsMoving);
    }

    // Explosion immunity, part two — and the part that actually matters.
    //
    // The 3,600,000 blast resistance set in PGBlocks only protects a grave that already exists
    // when an explosion goes off. It does nothing about the explosion that *created* the grave,
    // which is the overwhelmingly common case: creeper, TNT, ghast fireball, end crystal, bed in
    // the Nether. Vanilla's order is:
    //
    //   Explosion.explode()           ray-casts and collects `toBlow`, THEN damages entities. Air
    //                                 cells land in `toBlow` too, because
    //                                 getBlockExplosionResistance returns Optional.empty() for air
    //                                 so nothing is subtracted and the ray travels on. The player's
    //                                 death — and with it LivingDropsEvent and this mod's entire
    //                                 placement cascade — runs synchronously inside entity.hurt(),
    //                                 i.e. still inside this call.
    //   Explosion.finalizeExplosion() walks the already-collected `toBlow`, re-reads each position
    //                                 and removes whatever is no longer air. Blast resistance is
    //                                 NOT re-checked here.
    //
    // So the grave is placed into a cell that is already queued for removal, and the very
    // explosion that killed the player deletes it — and every item in it — microseconds later.
    // Overriding this Forge hook, which replaces finalizeExplosion's removeBlock + wasExploded
    // pair and exists precisely so a block can account for its BE, makes the grave genuinely
    // immune rather than only nominally immune.
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        // Deliberately empty: survive the blast, keep the BE, drop nothing.
    }

    // Belt and braces on the same path: finalizeExplosion consults this before running the loot
    // table for a block it is about to blow up. noLootTable() already makes that a no-op, but
    // saying no here keeps the intent explicit and skips the work.
    @Override
    public boolean dropFromExplosion(Explosion explosion) {
        return false;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    // Mimic bedrock for non-owners during the protection window: progress never advances, so the
    // client-side break prediction never disappears the block (no "ghost break" flash). Owner
    // UUID is synced to the client via getUpdateTag/onDataPacket, so canAccess works both sides.
    // BlockEvent.BreakEvent cancellation stays as backstop for creative-mode instabreak, which
    // bypasses this method.
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof GraveBlockEntity be && !be.canAccess(player)) {
            return 0.0f;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        // Non-collidable so players can walk onto the tile and interact; still selectable via getShape.
        return Shapes.empty();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GraveBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, PGBlockEntities.GRAVE.get(), GraveBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof GraveBlockEntity be)) return InteractionResult.PASS;

        if (!be.canAccess(player)) {
            player.displayClientMessage(Component.translatable("perfectgraves.grave.protected"), true);
            return InteractionResult.FAIL;
        }

        // Default right-click = take everything (Corail Tombstone / SimpleTomb pattern — the
        // ecosystem norm for polished grave mods). Sneak-right-click opens the manual-browse
        // menu for cherry-picking. Vanilla suppresses use() when sneaking with a non-empty
        // hand so players can still place blocks on the grave.
        if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
            if (player.isShiftKeyDown()) {
                player.openMenu(be);
            } else {
                QuickPickup.perform(sp, sl, pos, be);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
