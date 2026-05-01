package com.risen.perfectgraves.virtual;

import com.risen.perfectgraves.config.PGConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// SavedData attached to the overworld (`world/data/perfectgraves_virtual.dat`). Survives
// player.dat corruption because data lives with the world. Standard Forge pattern: setDirty()
// on every mutation, save() serializes, load() constructs.
public class VirtualGraveData extends SavedData {

    public static final String NAME = "perfectgraves_virtual";

    private final Map<UUID, List<VirtualGrave>> byOwner = new HashMap<>();
    private final Map<UUID, VirtualGrave> byGraveId = new HashMap<>();

    public static VirtualGraveData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
            VirtualGraveData::load,
            VirtualGraveData::new,
            NAME
        );
    }

    public void add(VirtualGrave grave) {
        List<VirtualGrave> list = byOwner.computeIfAbsent(grave.ownerId(), k -> new ArrayList<>());
        list.add(grave);
        byGraveId.put(grave.id(), grave);

        // Cap retained graves per player; evict oldest-first.
        int cap = PGConfig.COMMON.maxGravesPerPlayer.get();
        while (list.size() > cap) {
            VirtualGrave evicted = list.remove(0);
            byGraveId.remove(evicted.id());
        }
        setDirty();
    }

    public List<VirtualGrave> getFor(UUID ownerId) {
        return Collections.unmodifiableList(byOwner.getOrDefault(ownerId, List.of()));
    }

    public Optional<VirtualGrave> find(UUID graveId) {
        return Optional.ofNullable(byGraveId.get(graveId));
    }

    public Optional<VirtualGrave> remove(UUID graveId) {
        VirtualGrave g = byGraveId.remove(graveId);
        if (g == null) return Optional.empty();
        List<VirtualGrave> list = byOwner.get(g.ownerId());
        if (list != null) list.removeIf(v -> v.id().equals(graveId));
        setDirty();
        return Optional.of(g);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (VirtualGrave g : byGraveId.values()) {
            list.add(g.toNbt());
        }
        tag.put("graves", list);
        return tag;
    }

    public static VirtualGraveData load(CompoundTag tag) {
        VirtualGraveData data = new VirtualGraveData();
        ListTag list = tag.getList("graves", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            VirtualGrave g = VirtualGrave.fromNbt(list.getCompound(i));
            data.byOwner.computeIfAbsent(g.ownerId(), k -> new ArrayList<>()).add(g);
            data.byGraveId.put(g.id(), g);
        }
        return data;
    }
}
