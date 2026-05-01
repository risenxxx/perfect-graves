package com.risen.perfectgraves.registry;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.grave.GraveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class PGBlocks {

    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, PerfectGraves.MOD_ID);

    public static final RegistryObject<GraveBlock> GRAVE = BLOCKS.register("grave",
        () -> new GraveBlock(BlockBehaviour.Properties.copy(Blocks.BARRIER)
            .strength(1.0f, 3600000.0f)
            .noOcclusion()
            .noLootTable()
            .pushReaction(PushReaction.BLOCK)
            .isRedstoneConductor((s, l, p) -> false)
            .isSuffocating((s, l, p) -> false)
            .isViewBlocking((s, l, p) -> false)));

    private PGBlocks() {}
}
