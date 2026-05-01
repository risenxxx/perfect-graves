package com.risen.perfectgraves.virtual;

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
import java.util.UUID;

public record VirtualGrave(
    UUID id,
    UUID ownerId,
    String ownerName,
    BlockPos deathPos,
    ResourceKey<Level> dimension,
    long timestampMillis,
    List<ItemStack> items,
    int xp,
    String causeJson
) {
    // Decode the cause Component once on render. Mirrors DeathHistoryEntry.causeComponent — same
    // canonical wire form so mod-defined translatable death messages survive correctly.
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
        tag.put("DeathPos", NbtUtils.writeBlockPos(deathPos));
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Timestamp", timestampMillis);
        tag.putInt("XP", xp);
        if (causeJson != null && !causeJson.isEmpty()) tag.putString("CauseJson", causeJson);

        ListTag itemList = new ListTag();
        for (ItemStack s : items) {
            if (!s.isEmpty()) itemList.add(s.save(new CompoundTag()));
        }
        tag.put("Items", itemList);
        return tag;
    }

    public static VirtualGrave fromNbt(CompoundTag tag) {
        UUID id = tag.getUUID("Id");
        UUID ownerId = tag.getUUID("OwnerId");
        String ownerName = tag.getString("OwnerName");
        BlockPos pos = NbtUtils.readBlockPos(tag.getCompound("DeathPos"));
        ResourceKey<Level> dim = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(tag.getString("Dimension"))
        );
        long ts = tag.getLong("Timestamp");
        int xp = tag.getInt("XP");
        // Optional — pre-causeJson saves load with an empty cause and just skip the lore line.
        String causeJson = tag.contains("CauseJson", Tag.TAG_STRING) ? tag.getString("CauseJson") : "";

        ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);
        List<ItemStack> items = new ArrayList<>(itemList.size());
        for (int i = 0; i < itemList.size(); i++) {
            items.add(ItemStack.of(itemList.getCompound(i)));
        }

        return new VirtualGrave(id, ownerId, ownerName, pos, dim, ts, items, xp, causeJson);
    }
}
