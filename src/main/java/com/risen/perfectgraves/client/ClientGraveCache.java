package com.risen.perfectgraves.client;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.grave.GraveBlockEntity;
import com.risen.perfectgraves.registry.PGBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Persists grave positions client-side so markers stay visible past chunk-load distance and
// across reconnects. Keyed per-world (single-player save name; server address) so caches don't
// leak between unrelated worlds. Stale-entry handling has two layers:
//   (1) deathTime check at render time. If a cached entry's deathGameTime > the current world's
//       game tick, the entry is from a future timeline relative to the loaded world (e.g. you
//       loaded a backup) and gets evicted on sight. Game time is monotonic — /time set only
//       affects dayTime — so this is reliable.
//   (2) lazy eviction on ChunkEvent.Load. When a chunk arrives and a cached entry's pos is
//       inside it but the block isn't our grave anymore (someone broke it; self-destruct fired
//       while you weren't looking), drop the entry. This is the only way to detect "broken
//       since last sync" cases since the cache itself can't observe distant chunk events.
public final class ClientGraveCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record Entry(BlockPos pos, ResourceKey<Level> dim, GameProfile owner, long deathGameTime) {}

    // Composite key: world dimension + world position. Block coords are unique within a single
    // dimension; the cache file is already per-world, so dim+pos covers everything.
    public record DimPos(ResourceKey<Level> dim, BlockPos pos) {}

    private static final Map<DimPos, Entry> ENTRIES = new ConcurrentHashMap<>();
    @Nullable private static String currentWorldKey = null;

    private ClientGraveCache() {}

    // Refresh the cache entry from a freshly-loaded BE. Called from GraveBlockEntity.onLoad and
    // GraveBlockEntity.load on the client. Idempotent — replaces any existing entry at the
    // same pos. Persists to disk eagerly so the entry survives even if LevelEvent.Unload
    // doesn't fire (which on some setups it doesn't, e.g. dim changes within the same world
    // where vanilla just swaps the ClientLevel without triggering an explicit unload).
    public static void captureFromBE(GraveBlockEntity be) {
        if (be.getLevel() == null || !be.getLevel().isClientSide) return;
        if (be.getOwner() == null) return;

        // Lazy world-key initialization. If onWorldLoad hasn't fired yet for this session
        // (Forge's LevelEvent.Load ordering relative to incoming BE network packets isn't
        // reliable — BE.load can run before LevelEvent.Load has a chance to set the key),
        // compute it here and pre-load prior entries from disk so the new entry being added
        // doesn't get clobbered later.
        if (currentWorldKey == null) {
            String key = computeWorldKey(Minecraft.getInstance());
            if (key != null) {
                currentWorldKey = key;
                load();
            }
        }

        ResourceKey<Level> dim = be.getLevel().dimension();
        BlockPos pos = be.getBlockPos();
        Entry prev = ENTRIES.put(
            new DimPos(dim, pos),
            new Entry(pos, dim, be.getOwner(), be.getDeathGameTime())
        );
        boolean changed = (prev == null || prev.deathGameTime != be.getDeathGameTime());
        if (changed) {
            save();
        }
    }

    public static Collection<Entry> all() {
        return Collections.unmodifiableCollection(ENTRIES.values());
    }

    public static void evictAt(ResourceKey<Level> dim, BlockPos pos) {
        Entry removed = ENTRIES.remove(new DimPos(dim, pos));
        if (removed != null) save();
    }

    // Evict cached entries that are "in the future" relative to the supplied game tick. The
    // intent is detecting backup-loaded worlds where on-disk entries were saved at a higher
    // game tick than the loaded backup's clock. The buffer (200 ticks ≈ 10 seconds) is critical:
    // when a grave is placed server-side at tick T and the client receives the BE data packet,
    // there's a brief window where `client.level.getGameTime()` is still T-1 because it advances
    // on its own client tick. Without the buffer, we'd prune the just-captured entry on the
    // next render frame, before chunks could re-sync it. Real backup-load deltas are typically
    // many minutes / hours, so 10 seconds of slack is safe.
    public static void pruneFutureEntries(long currentTick) {
        long maxAllowedDeathTime = currentTick + 200L;
        ENTRIES.values().removeIf(e -> e.deathGameTime > maxAllowedDeathTime);
    }

    // World lifecycle. Triggered from GraveHologramRenderer's existing LevelEvent handlers so we
    // don't end up with two parallel world-event subscribers.
    //
    // Important subtlety: on dimension change within the same world, vanilla's
    // ClientPacketListener.handleRespawn replaces the ClientLevel without firing
    // LevelEvent.Unload on the old one — only the new one's LevelEvent.Load fires. If we
    // unconditionally cleared+reloaded here we'd drop any in-memory cache entries that the disk
    // file didn't already have (e.g. a freshly-placed grave the player just saw the hologram of
    // before dying and respawning to a different dimension). Solution: only clear+reload when
    // the *world key* actually changes (e.g. disconnect → join different server, load different
    // SP save). Dimension changes within the same world keep the in-memory state intact.
    public static void onWorldLoad(Minecraft mc) {
        String newKey = computeWorldKey(mc);
        if (newKey == null) return;
        if (newKey.equals(currentWorldKey)) return;
        ENTRIES.clear();
        currentWorldKey = newKey;
        load();
    }

    // Called when a ClientLevel is unloaded. We INTENTIONALLY DON'T clear ENTRIES or null out
    // currentWorldKey here. Forge's event ordering on dimension change is unpredictable across
    // versions and code paths — vanilla 1.20.1's Minecraft.setLevel triggers Unload while
    // mc.level still points to the old level (so leavingWorld looks true), and other code
    // paths can fire Load first then Unload. Either way, clearing here risks dropping the
    // freshly-captured death grave from memory before the next Load can see the same world key
    // and preserve it.
    //
    // Cleanup of stale state happens entirely in onWorldLoad when newKey != currentWorldKey
    // (different world entirely). For dim changes within the same world, in-memory entries
    // stay valid. For real exits (game close / disconnect), JVM teardown handles memory; the
    // save below ensures disk is current first.
    public static void onWorldUnload(boolean leavingWorld) {
        save();
    }

    // Lazy eviction on chunk load: when a chunk arrives client-side, walk our cached entries in
    // that chunk's footprint and check the actual block at each pos. If it's no longer our grave
    // block, the grave was destroyed at some point we couldn't observe — drop the cached entry.
    public static void onChunkLoadCheck(ClientLevel level, int chunkX, int chunkZ) {
        ResourceKey<Level> dim = level.dimension();
        int xMin = chunkX << 4, xMax = xMin + 15;
        int zMin = chunkZ << 4, zMax = zMin + 15;
        for (Entry e : ENTRIES.values()) {
            if (!e.dim.equals(dim)) continue;
            BlockPos p = e.pos;
            if (p.getX() < xMin || p.getX() > xMax) continue;
            if (p.getZ() < zMin || p.getZ() > zMax) continue;
            BlockState state = level.getBlockState(p);
            if (!state.is(PGBlocks.GRAVE.get())) {
                ENTRIES.remove(new DimPos(dim, p));
            }
        }
    }

    @Nullable
    private static String computeWorldKey(Minecraft mc) {
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            // Single-player: the level name is unique per save folder.
            return "sp_" + sanitize(mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        var server = mc.getCurrentServer();
        if (server != null) {
            return "mp_" + sanitize(server.ip);
        }
        return null;
    }

    // Filename-safe key. World names and server addresses can contain `/`, `:`, etc.; reduce to
    // alnum + a few safe punctuation chars. Collisions are theoretically possible (two server
    // addresses sanitizing to the same string) but vanishingly rare in practice.
    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static Path cacheFile() {
        return FMLPaths.CONFIGDIR.get()
            .resolve(PerfectGraves.MOD_ID)
            .resolve("marker_cache")
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
                g.putInt("x", e.pos.getX());
                g.putInt("y", e.pos.getY());
                g.putInt("z", e.pos.getZ());
                g.putString("dim", e.dim.location().toString());
                if (e.owner.getId() != null) g.putUUID("ownerId", e.owner.getId());
                if (e.owner.getName() != null) g.putString("ownerName", e.owner.getName());
                g.putLong("deathTime", e.deathGameTime);
                list.add(g);
            }
            root.put("graves", list);
            NbtIo.writeCompressed(root, file.toFile());
        } catch (IOException ex) {
            LOGGER.warn("[{}] failed to save marker cache for world {}: {}",
                PerfectGraves.MOD_ID, currentWorldKey, ex.getMessage());
        }
    }

    private static void load() {
        Path file = cacheFile();
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file.toFile());
            ListTag list = root.getList("graves", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag g = list.getCompound(i);
                BlockPos pos = new BlockPos(g.getInt("x"), g.getInt("y"), g.getInt("z"));
                ResourceLocation dimLoc = ResourceLocation.tryParse(g.getString("dim"));
                if (dimLoc == null) continue;
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimLoc);
                UUID ownerId = g.hasUUID("ownerId") ? g.getUUID("ownerId") : null;
                String ownerName = g.contains("ownerName", Tag.TAG_STRING) ? g.getString("ownerName") : null;
                long deathTime = g.getLong("deathTime");
                if (ownerId == null && (ownerName == null || ownerName.isEmpty())) continue;
                GameProfile owner = new GameProfile(ownerId, ownerName);
                ENTRIES.put(new DimPos(dim, pos), new Entry(pos, dim, owner, deathTime));
            }
        } catch (IOException ex) {
            LOGGER.warn("[{}] failed to load marker cache for world {}: {}",
                PerfectGraves.MOD_ID, currentWorldKey, ex.getMessage());
        }
    }
}
