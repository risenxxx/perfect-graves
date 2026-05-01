package com.risen.perfectgraves.grave;

import com.mojang.authlib.GameProfile;
import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.registry.PGBlockEntities;
import com.risen.perfectgraves.util.NbtKeys;
import com.risen.perfectgraves.util.PGLog;
import com.risen.perfectgraves.virtual.VirtualGrave;
import com.risen.perfectgraves.virtual.VirtualGraveData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GraveBlockEntity extends BlockEntity implements Container, MenuProvider {

    // Server-side index: owner UUID → loaded grave BEs they own. Lets OwnerPresenceSync push
    // an immediate BE update to observers at login/logout time without scanning all chunks.
    // Plain HashMap is fine — all server BE lifecycle calls happen on the main server thread.
    private static final Map<UUID, Set<GraveBlockEntity>> SERVER_INDEX = new HashMap<>();

    @Nullable private GameProfile owner;
    private NonNullList<ItemStack> items = NonNullList.withSize(0, ItemStack.EMPTY);
    private int xp;
    private long deathGameTime;

    // Protection timer state. onlineTicksElapsed advances only when the owner is online
    // (or when pauseProtectionWhileOffline=false). Comparison target is protectionTotalTicks,
    // snapshotted at grave creation so mid-flight config changes don't retro-apply.
    private long onlineTicksElapsed;
    private long protectionTotalTicks;
    private boolean protectionExpired;

    // Self-destruct is real-time (doesn't pause while offline). 0 means "never".
    private long selfDestructAt;

    // Client-facing projected protection end time in game ticks. Server writes this into
    // saveAdditional each time the BE syncs (derived from the counter fields above), the
    // client reads it via load() and uses it for a smooth `endsAt - level.gameTime()`
    // countdown. Server never consults this field for its own logic — the source of truth
    // stays onlineTicksElapsed/protectionTotalTicks.
    private long protectionEndsAtGameTime;

    // Client-facing flag derived at sync time: is the protection timer paused right now?
    // True only when pauseProtectionWhileOffline=true AND the owner is currently offline.
    // The renderer uses this (not a tab-list guess) to choose between the smooth
    // gameTime-extrapolation formula and the frozen "Protected for Xm Ys" formula.
    private boolean protectionPaused;

    // Vanilla-formatted death message (e.g. "Patbox was slain by Zombie", "... fell from a high
    // place"). Captured at death time in DeathEventHandler. Null for legacy graves from before
    // this feature shipped — the renderer falls back to a static "%s was killed" line.
    @Nullable private net.minecraft.network.chat.Component deathMessage;

    public GraveBlockEntity(BlockPos pos, BlockState state) {
        super(PGBlockEntities.GRAVE.get(), pos, state);
    }

    // Client-side: register with the hologram tracker so the post-translucent render pass knows
    // this grave exists, AND refresh the persistent marker cache so this grave's marker remains
    // visible after the chunk unloads or across reconnects.
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && this.level.isClientSide) {
            com.risen.perfectgraves.client.ClientGraveTracker.register(this);
            com.risen.perfectgraves.client.ClientGraveCache.captureFromBE(this);
        } else {
            // Server side. After NBT load(), owner is populated, so we can index now.
            // For brand-new graves the owner is null here; setContents() registers later.
            serverIndexAdd();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null && !this.level.isClientSide) {
            // Covers both block-broken and chunk-unload paths (vanilla calls setRemoved on each
            // BE during LevelChunk.clearAllBlockEntities). Re-registers via onLoad on chunk reload.
            serverIndexRemove();
        }
        if (this.level != null && this.level.isClientSide) {
            com.risen.perfectgraves.client.ClientGraveTracker.unregister(this);

            // Distinguish "block broken / picked up" from "chunk unloaded":
            //   - Block broken: ClientLevel.setBlock(pos, AIR) keeps the chunk in the array —
            //     getChunk returns a real LevelChunk. Block at pos is now non-grave.
            //   - Chunk unloaded: ClientChunkCache replaces the chunk slot with null. getChunk
            //     returns the singleton EmptyLevelChunk placeholder.
            // hasChunkAt / isLoaded both return true in BOTH cases (because they internally
            // accept the empty placeholder), so we have to check the chunk type explicitly.
            net.minecraft.world.level.chunk.ChunkAccess chunk = this.level.getChunk(
                net.minecraft.core.SectionPos.blockToSectionCoord(this.worldPosition.getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(this.worldPosition.getZ()),
                net.minecraft.world.level.chunk.ChunkStatus.FULL, false);
            boolean realChunkLoaded = chunk instanceof net.minecraft.world.level.chunk.LevelChunk
                && !(chunk instanceof net.minecraft.world.level.chunk.EmptyLevelChunk);
            if (realChunkLoaded) {
                BlockState here = this.level.getBlockState(this.worldPosition);
                if (!here.is(com.risen.perfectgraves.registry.PGBlocks.GRAVE.get())) {
                    // Block was actually replaced (broken / picked up), not chunk unload.
                    com.risen.perfectgraves.client.ClientGraveCache.evictAt(
                        this.level.dimension(), this.worldPosition);
                }
            }
        }
    }

    public void setContents(List<ItemStack> pool, int xpAmount, GameProfile ownerProfile, long now,
                            @Nullable net.minecraft.network.chat.Component deathMessage) {
        this.items = NonNullList.withSize(Math.max(pool.size(), 1), ItemStack.EMPTY);
        for (int i = 0; i < pool.size(); i++) {
            this.items.set(i, pool.get(i));
        }
        this.xp = xpAmount;
        this.owner = ownerProfile;
        this.deathGameTime = now;
        this.deathMessage = deathMessage;
        this.onlineTicksElapsed = 0;

        long protectionSeconds = PGConfig.COMMON.protectionTimeSeconds.get();
        this.protectionTotalTicks = protectionSeconds * 20L;
        this.protectionExpired = protectionTotalTicks <= 0;

        long selfDestructSeconds = PGConfig.COMMON.selfDestructTimeSeconds.get();
        this.selfDestructAt = selfDestructSeconds > 0 ? now + selfDestructSeconds * 20L : 0L;

        setChanged();
        // Push owner/items/timer state to nearby clients — without this the BER sees owner==null
        // and skips render entirely, making the grave look like empty air.
        if (this.level != null && !this.level.isClientSide) {
            BlockState state = getBlockState();
            this.level.sendBlockUpdated(worldPosition, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            // Owner is now populated; register in the per-owner index so login/logout sync can
            // find this grave without scanning all loaded chunks.
            serverIndexAdd();
        }
    }

    public boolean canAccess(Player player) {
        if (protectionExpired) return true;
        if (owner == null) return true;
        return owner.getId() != null && owner.getId().equals(player.getUUID());
    }

    @Nullable
    public GameProfile getOwner() { return owner; }
    public int getXp() { return xp; }
    @Nullable public net.minecraft.network.chat.Component getDeathMessage() { return deathMessage; }

    // Atomically extract stored XP. Returns the amount taken and zeroes the BE in the same call
    // so rapid menu-button clicks can't double-grant. Sync is explicit (same pattern as setContents).
    public int takeXp() {
        if (xp <= 0) return 0;
        int amount = xp;
        xp = 0;
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            BlockState state = getBlockState();
            this.level.sendBlockUpdated(worldPosition, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        return amount;
    }
    public long getDeathGameTime() { return deathGameTime; }
    public long getOnlineTicksElapsed() { return onlineTicksElapsed; }
    public long getProtectionTotalTicks() { return protectionTotalTicks; }
    public boolean isProtectionExpired() { return protectionExpired; }
    public long getSelfDestructAt() { return selfDestructAt; }
    public long getProtectionEndsAtGameTime() { return protectionEndsAtGameTime; }
    public boolean isProtectionPaused() { return protectionPaused; }

    // Server-side index management. Idempotent — re-registering an already-indexed BE is a no-op.
    private void serverIndexAdd() {
        if (this.level == null || this.level.isClientSide) return;
        if (this.owner == null || this.owner.getId() == null) return;
        SERVER_INDEX.computeIfAbsent(this.owner.getId(), k -> new HashSet<>()).add(this);
    }

    private void serverIndexRemove() {
        if (this.owner == null || this.owner.getId() == null) return;
        Set<GraveBlockEntity> set = SERVER_INDEX.get(this.owner.getId());
        if (set == null) return;
        set.remove(this);
        if (set.isEmpty()) SERVER_INDEX.remove(this.owner.getId());
    }

    // Called by OwnerPresenceSync on PlayerLoggedInEvent / PlayerLoggedOutEvent. Pushes a fresh
    // BE update to all observers of each grave owned by `ownerUuid` so the client's
    // onlineTicksElapsed / protectionEndsAtGameTime are aligned to the moment the owner's
    // presence flipped — eliminates the "hologram jumps a few seconds" effect that comes from
    // letting the periodic 10s sync drift past the login/logout transition.
    public static void syncForOwner(UUID ownerUuid) {
        Set<GraveBlockEntity> set = SERVER_INDEX.get(ownerUuid);
        if (set == null || set.isEmpty()) return;
        for (GraveBlockEntity be : set) {
            if (be.isRemoved()) continue;
            if (!(be.level instanceof ServerLevel sl)) continue;
            BlockState state = sl.getBlockState(be.worldPosition);
            if (!state.is(com.risen.perfectgraves.registry.PGBlocks.GRAVE.get())) continue;
            sl.sendBlockUpdated(be.worldPosition, state, state,
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    // Server-side tick. 20-tick gated to keep per-tick cost low when many graves are placed.
    // Two timers coexist here: protection (gameplay — expires after N online seconds, pauses
    // while owner offline) and self-destruct (cleanup — real-time, runs regardless of protection
    // state). A single early-return-if-expired would suppress the self-destruct check forever
    // after protection ends, so each timer is gated independently.
    public static void serverTick(Level level, BlockPos pos, BlockState state, GraveBlockEntity be) {
        if (level.getGameTime() % 20L != 0L) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        boolean justExpired = false;
        if (!be.protectionExpired) {
            boolean ownerOnline = be.owner != null
                && be.owner.getId() != null
                && server.getPlayerList().getPlayer(be.owner.getId()) != null;
            boolean pauseOffline = PGConfig.COMMON.pauseProtectionWhileOffline.get();

            if (!pauseOffline || ownerOnline) {
                be.onlineTicksElapsed += 20L;
                // Mark the chunk unsaved — without this the save-on-exit path sees the chunk
                // as clean and skips writing, so the counter reverts to whatever value last
                // triggered setChanged() (often 0 from setContents at grave creation).
                be.setChanged();
            }

            if (be.onlineTicksElapsed >= be.protectionTotalTicks) {
                be.protectionExpired = true;
                be.setChanged();
                justExpired = true;
                PGLog.debug(PGLog.GRAVE, "protection expired @ {}", pos);
            }
        }

        // Keep the client's hologram timers accurate. setChanged() alone only marks the chunk
        // dirty for save — it does not push BE data to clients, so without this re-sync the
        // client's onlineTicksElapsed stays at its initial value and the countdown freezes.
        // Sync on two edges: (a) every 10s while the timer is still ticking (skip once expired
        // so we're not pinging clients forever), (b) immediately when protection flips to
        // expired so the hologram's protection line disappears in real time.
        boolean tenSecondTick = level.getGameTime() % 200L == 0L;
        boolean wantSync = justExpired || (!be.protectionExpired && tenSecondTick);
        if (wantSync && level instanceof ServerLevel sl) {
            sl.sendBlockUpdated(pos, state, state,
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }

        // Self-destruct: per-config behavior. VIRTUAL_GRAVE packs contents into a restorable VG
        // so the owner can /grave restore later; DESTROY just removes the block and loses items.
        // Runs regardless of protection state — once protection has expired a grave is just a
        // public container waiting to decay.
        if (be.selfDestructAt > 0L && level.getGameTime() >= be.selfDestructAt) {
            performSelfDestruct(level, pos, be);
        }
    }

    private static void performSelfDestruct(Level level, BlockPos pos, GraveBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        PGConfig.SelfDestructMode mode = PGConfig.COMMON.onSelfDestruct.get();

        if (mode == PGConfig.SelfDestructMode.VIRTUAL_GRAVE
            && be.owner != null
            && be.owner.getId() != null) {

            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < be.items.size(); i++) {
                ItemStack s = be.items.get(i);
                if (!s.isEmpty()) items.add(s.copy());
            }

            if (!items.isEmpty()) {
                String causeJson = be.deathMessage != null
                    ? net.minecraft.network.chat.Component.Serializer.toJson(be.deathMessage)
                    : "";
                VirtualGrave vg = new VirtualGrave(
                    UUID.randomUUID(),
                    be.owner.getId(),
                    be.owner.getName() != null ? be.owner.getName() : "unknown",
                    pos,
                    sl.dimension(),
                    System.currentTimeMillis(),
                    items,
                    be.xp,
                    causeJson
                );
                VirtualGraveData.get(sl.getServer()).add(vg);
                PGLog.info(PGLog.GRAVE, "self-destruct @ {} → virtual grave {} ({} items, {} xp)",
                    pos, vg.id(), items.size(), be.xp);

                // Notify the owner (if online) so they discover the conversion via chat instead
                // of finding the grave gone with no explanation. Offline owners just see the new
                // entry the next time they run /grave list — acceptable per the simpler-path UX.
                net.minecraft.server.level.ServerPlayer ownerPlayer =
                    sl.getServer().getPlayerList().getPlayer(be.owner.getId());
                if (ownerPlayer != null) {
                    com.risen.perfectgraves.placement.PlacementCascade.notifyVirtualGraveCreated(ownerPlayer);
                }
            } else {
                PGLog.info(PGLog.GRAVE, "self-destruct @ {}: empty grave, removing block", pos);
            }
        } else if (mode == PGConfig.SelfDestructMode.DESTROY) {
            PGLog.error(PGLog.GRAVE, "self-destruct (DESTROY mode) @ {} — {} items and {} xp lost",
                pos, be.items.size(), be.xp);
        }

        be.items.clear();
        sl.removeBlock(pos, false);
    }

    // --- Container ---

    @Override
    public int getContainerSize() { return items.size(); }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, count);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) return;
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.level == null || this.level.getBlockEntity(worldPosition) != this) return false;
        double dx = worldPosition.getX() + 0.5 - player.getX();
        double dy = worldPosition.getY() + 0.5 - player.getY();
        double dz = worldPosition.getZ() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz <= 64.0;
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    // --- MenuProvider ---

    @Override
    public Component getDisplayName() {
        if (owner != null && owner.getName() != null) {
            return Component.translatable("container.perfectgraves.grave_of", owner.getName());
        }
        return Component.translatable("container.perfectgraves.grave");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new GraveContainerMenu(id, inv, this, this);
    }

    // --- Client sync ---
    // The BER reads owner/items/timers off the client-side BE. Without these overrides the client
    // BE has default-empty state (owner==null), the BER early-outs, and the invisible render
    // shape makes the whole grave look like nothing's there.

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    // Defensive cache hook for network packet path. The default `onDataPacket` calls
    // `handleUpdateTag` which calls `load`, and our `load` override calls `captureFromBE` —
    // but if a mod or vanilla change ever routes around `handleUpdateTag` (e.g., custom
    // partial-update packets), capturing here ensures the marker cache still picks up the
    // freshly-synced grave. Idempotent — `captureFromBE` no-ops the disk write when the
    // entry's deathTime hasn't changed.
    @Override
    public void onDataPacket(Connection conn, ClientboundBlockEntityDataPacket pkt) {
        super.onDataPacket(conn, pkt);
        if (this.level != null && this.level.isClientSide && this.owner != null) {
            com.risen.perfectgraves.client.ClientGraveCache.captureFromBE(this);
        }
    }

    // --- NBT ---

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        if (owner != null) {
            CompoundTag ownerTag = new CompoundTag();
            NbtUtils.writeGameProfile(ownerTag, owner);
            tag.put(NbtKeys.OWNER, ownerTag);
        }

        ListTag itemsList = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt("Slot", i);
                stack.save(slotTag);
                itemsList.add(slotTag);
            }
        }
        tag.put(NbtKeys.ITEMS, itemsList);
        tag.putInt(NbtKeys.ITEMS_SIZE, items.size());

        tag.putInt(NbtKeys.XP, xp);
        tag.putLong(NbtKeys.DEATH_TIME, deathGameTime);
        tag.putLong(NbtKeys.ONLINE_TICKS_ELAPSED, onlineTicksElapsed);
        tag.putLong(NbtKeys.PROTECTION_TOTAL_TICKS, protectionTotalTicks);
        tag.putBoolean(NbtKeys.PROTECTION_EXPIRED, protectionExpired);
        tag.putLong(NbtKeys.SELF_DESTRUCT_AT, selfDestructAt);

        // Derived client-facing timestamp. Computed fresh on every save/sync so the value the
        // client sees is always "projected end given what the server knows right now". When the
        // owner is online and the counter ticks, this stays constant (gameTime advances, remaining
        // shrinks by the same amount). When the counter is paused (owner offline + pauseOffline
        // enabled), gameTime advances while remaining doesn't, so this endpoint slides forward —
        // which is exactly the "protection pauses while offline" semantic.
        long nowGameTime = this.level != null ? this.level.getGameTime() : 0L;
        long remainingTicks = Math.max(0L, protectionTotalTicks - onlineTicksElapsed);
        tag.putLong(NbtKeys.PROTECTION_ENDS_AT_GAMETIME, nowGameTime + remainingTicks);

        // Server-authoritative pause flag. Computed fresh on each save/sync so the client doesn't
        // have to second-guess via tab list. When pauseProtectionWhileOffline=false the timer
        // never pauses, so this stays false even with the owner offline — the renderer keeps
        // using the smooth gameTime-extrapolation formula and the countdown stays per-tick smooth.
        boolean paused = false;
        if (this.level instanceof ServerLevel sl
            && this.owner != null && this.owner.getId() != null
            && PGConfig.COMMON.pauseProtectionWhileOffline.get()) {
            paused = sl.getServer().getPlayerList().getPlayer(this.owner.getId()) == null;
        }
        tag.putBoolean(NbtKeys.PROTECTION_PAUSED, paused);

        if (deathMessage != null) {
            tag.putString(NbtKeys.DEATH_MESSAGE,
                net.minecraft.network.chat.Component.Serializer.toJson(deathMessage));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        if (tag.contains(NbtKeys.OWNER, Tag.TAG_COMPOUND)) {
            this.owner = NbtUtils.readGameProfile(tag.getCompound(NbtKeys.OWNER));
        }

        int size = tag.contains(NbtKeys.ITEMS_SIZE) ? tag.getInt(NbtKeys.ITEMS_SIZE) : 0;
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
        ListTag itemsList = tag.getList(NbtKeys.ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < itemsList.size(); i++) {
            CompoundTag slotTag = itemsList.getCompound(i);
            int slot = slotTag.getInt("Slot");
            if (slot >= 0 && slot < size) {
                items.set(slot, ItemStack.of(slotTag));
            }
        }

        this.xp = tag.getInt(NbtKeys.XP);
        this.deathGameTime = tag.getLong(NbtKeys.DEATH_TIME);
        this.onlineTicksElapsed = tag.getLong(NbtKeys.ONLINE_TICKS_ELAPSED);
        this.protectionTotalTicks = tag.getLong(NbtKeys.PROTECTION_TOTAL_TICKS);
        this.protectionExpired = tag.getBoolean(NbtKeys.PROTECTION_EXPIRED);
        this.selfDestructAt = tag.getLong(NbtKeys.SELF_DESTRUCT_AT);
        this.protectionEndsAtGameTime = tag.getLong(NbtKeys.PROTECTION_ENDS_AT_GAMETIME);
        this.protectionPaused = tag.getBoolean(NbtKeys.PROTECTION_PAUSED);

        if (tag.contains(NbtKeys.DEATH_MESSAGE, Tag.TAG_STRING)) {
            String json = tag.getString(NbtKeys.DEATH_MESSAGE);
            this.deathMessage = net.minecraft.network.chat.Component.Serializer.fromJson(json);
        } else {
            this.deathMessage = null;
        }

        // Refresh the marker cache here as well as in onLoad. Reason: for newly-placed graves
        // the client receives the block-place packet first (BE created with no data → onLoad
        // fires with owner==null, cache.captureFromBE early-returns), THEN receives the BE
        // data packet which calls load() but does not re-fire onLoad. Without this hook the
        // cache would only catch the grave on a *subsequent* chunk reload, so freshly-placed
        // graves' markers would die at chunk-unload distance and never reappear.
        if (this.level != null && this.level.isClientSide && this.owner != null) {
            com.risen.perfectgraves.client.ClientGraveCache.captureFromBE(this);
        }
    }
}
