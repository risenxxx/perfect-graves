package com.risen.perfectgraves.registry;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.marker.LooseDropMarkerEntity;
import com.risen.perfectgraves.xp.SafeXPEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class PGEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, PerfectGraves.MOD_ID);

    public static final RegistryObject<EntityType<SafeXPEntity>> SAFE_XP = ENTITIES.register("safe_xp",
        () -> EntityType.Builder.<SafeXPEntity>of(SafeXPEntity::new, MobCategory.MISC)
            .sized(0.5f, 0.5f)
            .clientTrackingRange(8)
            .updateInterval(20)
            .build("safe_xp"));

    // Tracking range is in chunks (vanilla unit). 16 chunks ≈ 256 blocks — matches the default
    // markerMaxDistance so the entity is sent to the client at least once before its long-distance
    // marker becomes visible. The client persists the entry into ClientLooseDropCache on first
    // sight, so the marker stays visible past tracking range thereafter.
    public static final RegistryObject<EntityType<LooseDropMarkerEntity>> LOOSE_DROP_MARKER = ENTITIES.register("loose_drop_marker",
        () -> EntityType.Builder.<LooseDropMarkerEntity>of(LooseDropMarkerEntity::new, MobCategory.MISC)
            .sized(0.1f, 0.1f)
            .noSummon()
            .fireImmune()
            .clientTrackingRange(16)
            .updateInterval(Integer.MAX_VALUE)
            .build("loose_drop_marker"));

    private PGEntities() {}
}
