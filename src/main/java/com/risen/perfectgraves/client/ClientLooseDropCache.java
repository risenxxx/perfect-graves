package com.risen.perfectgraves.client;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.marker.LooseDropMarkerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Client-side cache of loose-drop markers, mirrored on ClientGraveCache. Markers come from
// vanilla entity-tracking when a LooseDropMarkerEntity enters client range; they're written to
// disk so the long-distance marker keeps rendering after the chunk unloads.
//
// Two cache files per world:
//   * loose_marker_cache/<key>.dat — active markers the local player should still see
//   * loose_marker_dismissed/<key>.dat — UUIDs the local player has already dismissed via
//     proximity. Persisted so wandering back into the entity's tracking range doesn't re-add
//     a marker the player already found.
public final class ClientLooseDropCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record Entry(UUID id, BlockPos pos, ResourceKey<Level> dim, GameProfile owner,
                        long deathGameTime, LooseDropMarkerEntity.DropKind kind) {}

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Set<UUID> DISMISSED = ConcurrentHashMap.newKeySet();
    @Nullable private static String currentWorldKey = null;

    private ClientLooseDropCache() {}

    // Idempotent capture from a tracked marker entity. Called every render frame for every
    // loaded marker — must be cheap on the no-op path. Skips entries the player has already
    // dismissed (proximity dismissal must not silently come back).
    public static void captureFromEntity(LooseDropMarkerEntity e) {
        if (e.level() == null || !e.level().isClientSide) return;
        if (e.getOwnerId().isEmpty()) return;

        if (currentWorldKey == null) {
            String key = computeWorldKey(Minecraft.getInstance());
            if (key != null) {
                currentWorldKey = key;
                load();
            }
        }

        UUID markerId = e.getUUID();
        if (DISMISSED.contains(markerId)) return;

        UUID ownerId = e.getOwnerId().get();
        String ownerName = e.getOwnerName();
        GameProfile owner = new GameProfile(ownerId, ownerName.isEmpty() ? null : ownerName);
        Entry next = new Entry(
            markerId,
            e.blockPosition(),
            e.level().dimension(),
            owner,
            e.getDeathGameTime(),
            e.getDropKind()
        );
        Entry prev = ENTRIES.put(markerId, next);
        boolean changed = (prev == null || !prev.equals(next));
        if (changed) {
            LOGGER.debug("[pg.loose-marker.capture] new markerId={} pos={} kind={} cacheSize={}",
                markerId, e.blockPosition(), e.getDropKind(), ENTRIES.size());
            save();
        }
    }

    public static Collection<Entry> all() {
        return Collections.unmodifiableCollection(ENTRIES.values());
    }

    // Per-viewer dismissal: drop from the cache and remember the marker id forever (per world)
    // so we don't recapture it next time we wander back into the entity's tracking range.
    public static void dismiss(UUID markerId) {
        boolean wasPresent = ENTRIES.remove(markerId) != null;
        boolean wasNew = DISMISSED.add(markerId);
        if (wasPresent || wasNew) save();
    }

    public static boolean isDismissed(UUID markerId) {
        return DISMISSED.contains(markerId);
    }

    public static void removeById(UUID markerId) {
        if (ENTRIES.remove(markerId) != null) save();
    }

    public static void onWorldLoad(Minecraft mc) {
        String newKey = computeWorldKey(mc);
        if (newKey == null) return;
        if (newKey.equals(currentWorldKey)) return;
        ENTRIES.clear();
        DISMISSED.clear();
        currentWorldKey = newKey;
        load();
    }

    public static void onWorldUnload(boolean leavingWorld) {
        save();
    }

    @Nullable
    private static String computeWorldKey(Minecraft mc) {
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return "sp_" + sanitize(mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        var server = mc.getCurrentServer();
        if (server != null) {
            return "mp_" + sanitize(server.ip);
        }
        return null;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static Path cacheFile() {
        return FMLPaths.CONFIGDIR.get()
            .resolve(PerfectGraves.MOD_ID)
            .resolve("loose_marker_cache")
            .resolve(currentWorldKey + ".dat");
    }

    private static void save() {
        if (currentWorldKey == null) return;
        Path file = cacheFile();
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (Entry e : ENTRIES.values()) {
                CompoundTag g = new CompoundTag();
                g.putUUID("id", e.id);
                g.putInt("x", e.pos.getX());
                g.putInt("y", e.pos.getY());
                g.putInt("z", e.pos.getZ());
                g.putString("dim", e.dim.location().toString());
                if (e.owner.getId() != null) g.putUUID("ownerId", e.owner.getId());
                if (e.owner.getName() != null) g.putString("ownerName", e.owner.getName());
                g.putLong("deathTime", e.deathGameTime);
                g.putByte("kind", (byte) e.kind.ordinal());
                list.add(g);
            }
            root.put("markers", list);

            ListTag dismissed = new ListTag();
            for (UUID id : DISMISSED) {
                CompoundTag t = new CompoundTag();
                t.putUUID("id", id);
                dismissed.add(t);
            }
            root.put("dismissed", dismissed);

            NbtIo.writeCompressed(root, file.toFile());
        } catch (IOException ex) {
            LOGGER.warn("[{}] failed to save loose-drop cache for world {}: {}",
                PerfectGraves.MOD_ID, currentWorldKey, ex.getMessage());
        }
    }

    private static void load() {
        Path file = cacheFile();
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file.toFile());
            ListTag list = root.getList("markers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag g = list.getCompound(i);
                if (!g.hasUUID("id")) continue;
                UUID id = g.getUUID("id");
                BlockPos pos = new BlockPos(g.getInt("x"), g.getInt("y"), g.getInt("z"));
                ResourceLocation dimLoc = ResourceLocation.tryParse(g.getString("dim"));
                if (dimLoc == null) continue;
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimLoc);
                UUID ownerId = g.hasUUID("ownerId") ? g.getUUID("ownerId") : null;
                String ownerName = g.contains("ownerName", Tag.TAG_STRING) ? g.getString("ownerName") : null;
                if (ownerId == null && (ownerName == null || ownerName.isEmpty())) continue;
                long deathTime = g.getLong("deathTime");
                LooseDropMarkerEntity.DropKind kind = LooseDropMarkerEntity.dropKindFromOrdinal(g.getByte("kind"));
                if (kind == null) kind = LooseDropMarkerEntity.DropKind.SAFE_DROP;
                GameProfile owner = new GameProfile(ownerId, ownerName);
                ENTRIES.put(id, new Entry(id, pos, dim, owner, deathTime, kind));
            }

            ListTag dismissed = root.getList("dismissed", Tag.TAG_COMPOUND);
            for (int i = 0; i < dismissed.size(); i++) {
                CompoundTag t = dismissed.getCompound(i);
                if (t.hasUUID("id")) DISMISSED.add(t.getUUID("id"));
            }

            // Sweep entries that were dismissed in a prior session and somehow still in cache
            // (shouldn't happen, but the sets are stored separately so be defensive).
            Set<UUID> stale = new HashSet<>();
            for (UUID id : ENTRIES.keySet()) {
                if (DISMISSED.contains(id)) stale.add(id);
            }
            for (UUID id : stale) ENTRIES.remove(id);
        } catch (IOException ex) {
            LOGGER.warn("[{}] failed to load loose-drop cache for world {}: {}",
                PerfectGraves.MOD_ID, currentWorldKey, ex.getMessage());
        }
    }
}
