package com.risen.perfectgraves.history;

import com.risen.perfectgraves.death.CascadeResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// One row of the per-player death log. Stored in DeathHistoryData (SavedData attached to the
// overworld). Items are full NBT snapshots at death time so the history stays accurate after
// the underlying grave / virtual grave is consumed. Mirrors VirtualGrave's record shape +
// NBT format conventions.
public record DeathHistoryEntry(
    UUID id,
    UUID ownerId,
    String ownerName,
    long timestampMillis,
    long deathGameTime,
    ResourceKey<Level> dimension,
    BlockPos deathPos,
    String causeJson,                          // Component.Serializer.toJson(deathMessage)
    CascadeResult outcome,
    Optional<BlockPos> outcomeBlockRef,        // GRAVE: where the grave block was placed
    Optional<UUID> outcomeVirtualRef,          // VIRTUAL_GRAVE: VirtualGrave UUID
    List<ItemStack> items,
    int xp
) {
    // Decode the cause Component once on render. `causeJson` is the canonical wire form so
    // mod-defined translatable death messages survive correctly.
    public Component causeComponent() {
        if (causeJson == null || causeJson.isEmpty()) return Component.empty();
        try {
            Component c = Component.Serializer.fromJson(causeJson);
            return c != null ? c : Component.empty();
        } catch (RuntimeException ex) {
            return Component.empty();
        }
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("OwnerId", ownerId);
        tag.putString("OwnerName", ownerName);
        tag.putLong("Timestamp", timestampMillis);
        tag.putLong("DeathGameTime", deathGameTime);
        tag.putString("Dimension", dimension.location().toString());
        tag.put("DeathPos", NbtUtils.writeBlockPos(deathPos));
        tag.putString("CauseJson", causeJson == null ? "" : causeJson);
        tag.putString("Outcome", outcome.name());
        outcomeBlockRef.ifPresent(p -> tag.put("OutcomeBlockRef", NbtUtils.writeBlockPos(p)));
        outcomeVirtualRef.ifPresent(u -> tag.putUUID("OutcomeVirtualRef", u));
        tag.putInt("XP", xp);

        ListTag itemList = new ListTag();
        for (ItemStack s : items) {
            if (!s.isEmpty()) itemList.add(s.save(new CompoundTag()));
        }
        tag.put("Items", itemList);
        return tag;
    }

    public static DeathHistoryEntry fromNbt(CompoundTag tag) {
        UUID id = tag.getUUID("Id");
        UUID ownerId = tag.getUUID("OwnerId");
        String ownerName = tag.getString("OwnerName");
        long ts = tag.getLong("Timestamp");
        long deathGameTime = tag.getLong("DeathGameTime");
        ResourceKey<Level> dim = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(tag.getString("Dimension"))
        );
        BlockPos pos = NbtUtils.readBlockPos(tag.getCompound("DeathPos"));
        String causeJson = tag.contains("CauseJson", Tag.TAG_STRING) ? tag.getString("CauseJson") : "";
        CascadeResult outcome;
        try {
            outcome = CascadeResult.valueOf(tag.getString("Outcome"));
        } catch (IllegalArgumentException ex) {
            outcome = CascadeResult.VANILLA_DROP;
        }
        Optional<BlockPos> outcomeBlockRef = tag.contains("OutcomeBlockRef", Tag.TAG_COMPOUND)
            ? Optional.of(NbtUtils.readBlockPos(tag.getCompound("OutcomeBlockRef")))
            : Optional.empty();
        Optional<UUID> outcomeVirtualRef = tag.hasUUID("OutcomeVirtualRef")
            ? Optional.of(tag.getUUID("OutcomeVirtualRef"))
            : Optional.empty();
        int xp = tag.getInt("XP");

        ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);
        List<ItemStack> items = new ArrayList<>(itemList.size());
        for (int i = 0; i < itemList.size(); i++) {
            items.add(ItemStack.of(itemList.getCompound(i)));
        }

        return new DeathHistoryEntry(id, ownerId, ownerName, ts, deathGameTime, dim, pos,
            causeJson, outcome, outcomeBlockRef, outcomeVirtualRef, items, xp);
    }
}
