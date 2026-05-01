package com.risen.perfectgraves.history;

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

// SavedData attached to the overworld at world/data/perfectgraves_history.dat. Mirrors
// VirtualGraveData: per-player ring buffer, oldest-first eviction when over cap, byId lookup
// for the detail menu. Per-tick cost is zero — only mutated on death events.
public class DeathHistoryData extends SavedData {

    public static final String NAME = "perfectgraves_history";

    private final Map<UUID, List<DeathHistoryEntry>> byOwner = new HashMap<>();
    private final Map<UUID, DeathHistoryEntry> byId = new HashMap<>();

    public static DeathHistoryData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
            DeathHistoryData::load,
            DeathHistoryData::new,
            NAME
        );
    }

    public void add(DeathHistoryEntry entry) {
        List<DeathHistoryEntry> list = byOwner.computeIfAbsent(entry.ownerId(), k -> new ArrayList<>());
        list.add(entry);
        byId.put(entry.id(), entry);

        int cap = PGConfig.COMMON.deathHistoryMaxPerPlayer.get();
        while (list.size() > cap) {
            DeathHistoryEntry evicted = list.remove(0);
            byId.remove(evicted.id());
        }
        setDirty();
    }

    // Newest-first view for menu rendering. Internal storage is insertion-order (oldest first)
    // so reverse on the way out.
    public List<DeathHistoryEntry> getFor(UUID ownerId) {
        List<DeathHistoryEntry> raw = byOwner.get(ownerId);
        if (raw == null || raw.isEmpty()) return List.of();
        List<DeathHistoryEntry> reversed = new ArrayList<>(raw);
        Collections.reverse(reversed);
        return Collections.unmodifiableList(reversed);
    }

    public Optional<DeathHistoryEntry> findById(UUID entryId) {
        return Optional.ofNullable(byId.get(entryId));
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (DeathHistoryEntry e : byId.values()) {
            list.add(e.toNbt());
        }
        tag.put("entries", list);
        return tag;
    }

    public static DeathHistoryData load(CompoundTag tag) {
        DeathHistoryData data = new DeathHistoryData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            DeathHistoryEntry e = DeathHistoryEntry.fromNbt(list.getCompound(i));
            data.byOwner.computeIfAbsent(e.ownerId(), k -> new ArrayList<>()).add(e);
            data.byId.put(e.id(), e);
        }
        return data;
    }
}
