package com.risen.perfectgraves.xp;

import com.risen.perfectgraves.registry.PGEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Single orb carrying the full saved XP pool, owner-locked pickup. Lives as one entity
// regardless of XP amount — no vanilla orb splitting.
public class SafeXPEntity extends Entity {

    private static final EntityDataAccessor<Integer> DATA_XP =
        SynchedEntityData.defineId(SafeXPEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
        SynchedEntityData.defineId(SafeXPEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    // Cosmetic tick count for rendering animation (driven by age, also used to skip pickup for 1s).
    private static final int PICKUP_DELAY_TICKS = 10;
    private static final int MAX_AGE_TICKS = 20 * 60 * 5; // 5 minutes — prevents orphan orbs piling up

    public SafeXPEntity(EntityType<? extends SafeXPEntity> type, Level level) {
        super(type, level);
    }

    public static SafeXPEntity create(Level level, Vec3 pos, int xpAmount, @Nullable UUID ownerId) {
        SafeXPEntity e = new SafeXPEntity(PGEntities.SAFE_XP.get(), level);
        e.setPos(pos.x, pos.y, pos.z);
        e.setXp(xpAmount);
        if (ownerId != null) e.setOwner(ownerId);
        return e;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_XP, 0);
        this.entityData.define(DATA_OWNER, Optional.empty());
    }

    @Override
    public void tick() {
        super.tick();

        // Minimal physics: tiny upward bob so it looks alive; no gravity so it stays where spawned.
        Vec3 v = getDeltaMovement();
        setDeltaMovement(v.x * 0.9, v.y * 0.9 + 0.005, v.z * 0.9);
        move(MoverType.SELF, getDeltaMovement());

        if (!level().isClientSide) {
            if (tickCount >= MAX_AGE_TICKS) {
                discard();
                return;
            }
            if (tickCount < PICKUP_DELAY_TICKS) return;

            List<Player> nearby = level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(1.0));
            for (Player p : nearby) {
                if (canBeAbsorbedBy(p)) {
                    absorb(p);
                    return;
                }
            }
        }
    }

    private boolean canBeAbsorbedBy(Player p) {
        Optional<UUID> owner = entityData.get(DATA_OWNER);
        return owner.isEmpty() || owner.get().equals(p.getUUID());
    }

    private void absorb(Player p) {
        p.giveExperiencePoints(getXp());
        discard();
    }

    public int getXp() { return entityData.get(DATA_XP); }
    public void setXp(int v) { entityData.set(DATA_XP, v); }
    public Optional<UUID> getOwnerId() { return entityData.get(DATA_OWNER); }
    public void setOwner(UUID id) { entityData.set(DATA_OWNER, Optional.of(id)); }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setXp(tag.getInt("XP"));
        if (tag.hasUUID("Owner")) setOwner(tag.getUUID("Owner"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("XP", getXp());
        getOwnerId().ifPresent(uuid -> tag.putUUID("Owner", uuid));
    }
}
