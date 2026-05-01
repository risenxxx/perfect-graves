package com.risen.perfectgraves.history;

import com.risen.perfectgraves.death.CascadeResult;
import com.risen.perfectgraves.registry.PGMenus;
import com.risen.perfectgraves.util.DimensionDisplay;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// Read-only detail view for one DeathHistoryEntry. 6-row chest layout: row 0 holds the back
// button + metadata skull + (when overflow) a "and N more" indicator; rows 1-5 (45 slots)
// show the inventory snapshot at death time.
public class DeathHistoryDetailMenu extends AbstractContainerMenu {

    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int DISPLAY_SIZE = COLS * ROWS;
    private static final int ITEMS_OFFSET = COLS;          // items start at slot 9
    private static final int ITEMS_CAPACITY = DISPLAY_SIZE - ITEMS_OFFSET; // 45
    private static final int SLOT_BACK = 0;
    private static final int SLOT_INFO = 1;
    private static final int SLOT_OVERFLOW = 8;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Server-side: backTarget describes how to rebuild the list menu when the back slot is
    // clicked. Null on the client.
    private final BackTarget backTarget;

    public record BackTarget(java.util.UUID ownerId, String ownerName) {}

    public DeathHistoryDetailMenu(int id, Inventory playerInv, DeathHistoryEntry entry) {
        super(PGMenus.HISTORY_DETAIL.get(), id);
        this.backTarget = entry == null
            ? null
            : new BackTarget(entry.ownerId(), entry.ownerName());

        Container display = new SimpleContainer(DISPLAY_SIZE);
        if (entry != null) {
            display.setItem(SLOT_BACK, buildBackItem());
            display.setItem(SLOT_INFO, buildInfoSkull(entry));

            int n = Math.min(entry.items().size(), ITEMS_CAPACITY);
            for (int i = 0; i < n; i++) {
                display.setItem(ITEMS_OFFSET + i, entry.items().get(i).copy());
            }
            int overflow = entry.items().size() - ITEMS_CAPACITY;
            if (overflow > 0) {
                display.setItem(SLOT_OVERFLOW, buildOverflowItem(overflow));
            }
        }

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col;
                addSlot(new DisplaySlot(display, idx, 8 + col * 18, 18 + row * 18));
            }
        }

        int invY = 18 + ROWS * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, invY + 58));
        }
    }

    public static DeathHistoryDetailMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        return new DeathHistoryDetailMenu(id, inv, null);
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        if (slotId == SLOT_BACK
            && clickType == ClickType.PICKUP
            && player instanceof ServerPlayer sp
            && backTarget != null) {
            sp.closeContainer();
            sp.openMenu(DeathHistoryListMenu.listProviderFor(
                backTarget.ownerId(), backTarget.ownerName(), sp.getServer()));
            return;
        }
        if (slotId >= 0 && slotId < DISPLAY_SIZE) {
            // Read-only display slots — swallow all clicks (no shift-pickup, no drop, etc.).
            return;
        }
        super.clicked(slotId, dragType, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    private static ItemStack buildBackItem() {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.setHoverName(Component.translatable("perfectgraves.history.detail.back")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(false)));
        return head;
    }

    private static ItemStack buildInfoSkull(DeathHistoryEntry entry) {
        ItemStack skull = new ItemStack(Items.SKELETON_SKULL);
        CompoundTag tag = skull.getOrCreateTag();
        skull.setHoverName(Component.translatable("perfectgraves.history.detail.skull_title",
            entry.ownerName()).withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)));

        String date = DATE_FMT.format(LocalDateTime.ofInstant(
            Instant.ofEpochMilli(entry.timestampMillis()), ZoneId.systemDefault()));
        String pos = entry.deathPos().getX() + ", " + entry.deathPos().getY() + ", " + entry.deathPos().getZ();
        Component dimName = DimensionDisplay.name(entry.dimension());

        ListTag lore = new ListTag();
        addLore(lore, Component.literal(date).withStyle(ChatFormatting.GRAY));
        addLore(lore, Component.translatable("perfectgraves.history.detail.cause",
            entry.causeComponent()).withStyle(ChatFormatting.YELLOW));
        addLore(lore, Component.translatable("perfectgraves.history.lore.position",
            dimName, Component.literal(pos)).withStyle(ChatFormatting.GRAY));
        addLore(lore, Component.translatable("perfectgraves.history.detail.outcome",
            Component.translatable(outcomeLabelKey(entry.outcome())),
            Component.literal(outcomeRefSuffix(entry)))
            .withStyle(outcomeColor(entry.outcome())));
        addLore(lore, Component.translatable("perfectgraves.history.lore.items_xp",
            entry.items().size(), entry.xp()).withStyle(ChatFormatting.GRAY));
        tag.getCompound("display").put("Lore", lore);
        return skull;
    }

    private static ItemStack buildOverflowItem(int overflow) {
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.setHoverName(Component.translatable("perfectgraves.history.detail.overflow", overflow)
            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        return paper;
    }

    private static void addLore(ListTag lore, Component line) {
        // Strip italic (vanilla auto-italicizes lore on items) so colors render cleanly.
        Component normalized = Component.empty().append(line)
            .withStyle(s -> s.withItalic(false));
        lore.add(StringTag.valueOf(Component.Serializer.toJson(normalized)));
    }

    private static String outcomeLabelKey(CascadeResult outcome) {
        return switch (outcome) {
            case GRAVE -> "perfectgraves.history.lore.outcome_grave";
            case VIRTUAL_GRAVE -> "perfectgraves.history.lore.outcome_virtual";
            case SAFE_DROP -> "perfectgraves.history.lore.outcome_safedrop";
            case VANILLA_DROP -> "perfectgraves.history.lore.outcome_vanilla";
            case RETURN_TO_INVENTORY -> "perfectgraves.history.lore.outcome_virtual";
        };
    }

    private static String outcomeRefSuffix(DeathHistoryEntry entry) {
        if (entry.outcomeBlockRef().isPresent()) {
            var p = entry.outcomeBlockRef().get();
            return " @ " + p.getX() + ", " + p.getY() + ", " + p.getZ();
        }
        if (entry.outcomeVirtualRef().isPresent()) {
            return " (id " + entry.outcomeVirtualRef().get().toString().substring(0, 8) + ")";
        }
        return "";
    }

    private static ChatFormatting outcomeColor(CascadeResult outcome) {
        return switch (outcome) {
            case GRAVE -> ChatFormatting.GREEN;
            case VIRTUAL_GRAVE, RETURN_TO_INVENTORY -> ChatFormatting.GOLD;
            case SAFE_DROP -> ChatFormatting.YELLOW;
            case VANILLA_DROP -> ChatFormatting.RED;
        };
    }

    private static class DisplaySlot extends Slot {
        DisplaySlot(Container c, int slot, int x, int y) {
            super(c, slot, x, y);
        }

        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }
}
