package com.risen.perfectgraves.marker;

import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.registry.PGEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

// Invisible marker entity placed at SAFE_DROP / VANILLA_DROP positions so the player has a
// visible waypoint back to their items when no grave block was placed. The actual on-screen
// rendering happens in GraveHologramRenderer, driven from ClientLooseDropCache — this entity
// is just the data carrier and the trigger for vanilla entity-tracking sync.
//
// Marker identity: the entity's vanilla UUID (`getUUID()`) doubles as the marker ID. Persisting
// across save/load is automatic, so per-viewer dismissal can key off it. No SavedData needed.
public class LooseDropMarkerEntity extends Entity {

    public enum DropKind { SAFE_DROP, VANILLA_DROP }

    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_ID =
        SynchedEntityData.defineId(LooseDropMarkerEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> DATA_OWNER_NAME =
        SynchedEntityData.defineId(LooseDropMarkerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> DATA_DEATH_TIME =
        SynchedEntityData.defineId(LooseDropMarkerEntity.class, EntityDataSerializers.LONG);
    // 0 = SAFE_DROP, 1 = VANILLA_DROP. Byte instead of String to keep the sync packet tiny.
    private static final EntityDataAccessor<Byte> DATA_KIND =
        SynchedEntityData.defineId(LooseDropMarkerEntity.class, EntityDataSerializers.BYTE);

    public LooseDropMarkerEntity(EntityType<? extends LooseDropMarkerEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static LooseDropMarkerEntity create(Level level, Vec3 pos, UUID ownerId,
                                               String ownerName, long deathGameTime, DropKind kind) {
        LooseDropMarkerEntity e = new LooseDropMarkerEntity(PGEntities.LOOSE_DROP_MARKER.get(), level);
        e.setPos(pos.x, pos.y, pos.z);
        e.entityData.set(DATA_OWNER_ID, Optional.of(ownerId));
        e.entityData.set(DATA_OWNER_NAME, ownerName);
        e.entityData.set(DATA_DEATH_TIME, deathGameTime);
        e.entityData.set(DATA_KIND, (byte) kind.ordinal());
        return e;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_OWNER_ID, Optional.empty());
        this.entityData.define(DATA_OWNER_NAME, "");
        this.entityData.define(DATA_DEATH_TIME, 0L);
        this.entityData.define(DATA_KIND, (byte) 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        int ttlSeconds = PGConfig.COMMON.looseDropMarkerTtlSeconds.get();
        if (ttlSeconds <= 0) return;
        // tickCount counts post-spawn ticks; persistAge survives save/load so reloading the world
        // doesn't reset the TTL clock.
        long ageTicks = (long) tickCount + (long) getPersistAge();
        if (ageTicks >= (long) ttlSeconds * 20L) {
            discard();
        }
    }

    // Markers must persist across save/load so the visual outlives a logout.
    @Override
    public boolean shouldBeSaved() { return true; }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSq) {
        // Vanilla considers far entities for client-side culling. Markers are rendered by the
        // hologram renderer reading the cache — but the entity itself still drives initial
        // capture, so we want the client to track at the configured marker distance plus a
        // safety margin. Track range is set in the EntityType.Builder; this method only affects
        // *render* distance, which we leave generous (no actual rendering happens here).
        return true;
    }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    public boolean isInvulnerable() { return true; }

    @Override
    public boolean isNoGravity() { return true; }

    @Override
    public boolean canBeCollidedWith() { return false; }

    @Override
    public boolean dampensVibrations() { return true; }

    public Optional<UUID> getOwnerId() { return entityData.get(DATA_OWNER_ID); }
    public String getOwnerName() { return entityData.get(DATA_OWNER_NAME); }
    public long getDeathGameTime() { return entityData.get(DATA_DEATH_TIME); }
    public DropKind getDropKind() {
        byte b = entityData.get(DATA_KIND);
        return b == 1 ? DropKind.VANILLA_DROP : DropKind.SAFE_DROP;
    }

    public int getPersistAge() { return persistAge; }
    private int persistAge = 0;

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("OwnerId")) {
            entityData.set(DATA_OWNER_ID, Optional.of(tag.getUUID("OwnerId")));
        }
        entityData.set(DATA_OWNER_NAME, tag.getString("OwnerName"));
        entityData.set(DATA_DEATH_TIME, tag.getLong("DeathTime"));
        entityData.set(DATA_KIND, tag.getByte("Kind"));
        this.persistAge = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        getOwnerId().ifPresent(uuid -> tag.putUUID("OwnerId", uuid));
        tag.putString("OwnerName", getOwnerName());
        tag.putLong("DeathTime", getDeathGameTime());
        tag.putByte("Kind", entityData.get(DATA_KIND));
        // Carry forward total age so TTL survives unload/reload.
        tag.putInt("Age", persistAge + tickCount);
    }

    @Nullable
    public static DropKind dropKindFromOrdinal(byte ordinal) {
        if (ordinal == 0) return DropKind.SAFE_DROP;
        if (ordinal == 1) return DropKind.VANILLA_DROP;
        return null;
    }
}
