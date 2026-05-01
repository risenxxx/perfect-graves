package com.risen.perfectgraves.registry;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.grave.GraveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class PGBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, PerfectGraves.MOD_ID);

    @SuppressWarnings("DataFlowIssue") // BlockEntityType.Builder.build(null) is the documented pattern
    public static final RegistryObject<BlockEntityType<GraveBlockEntity>> GRAVE =
        BLOCK_ENTITIES.register("grave",
            () -> BlockEntityType.Builder.of(GraveBlockEntity::new, PGBlocks.GRAVE.get()).build(null));

    private PGBlockEntities() {}
}
