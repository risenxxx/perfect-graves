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
import net.minecraft.world.MenuProvider;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// /grave history opens this. Mirrors VirtualGraveMenu structure but read-only and routes
// clicks to a per-entry detail view rather than restoring items.
public class DeathHistoryListMenu extends AbstractContainerMenu {

    private static final int COLS = 9;
    private static final int ROWS = 3;
    private static final int DISPLAY_SIZE = COLS * ROWS;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Server-side: entryIds[slot] maps a clicked card to its DeathHistoryEntry UUID. Null on
    // empty slots. Client-side this stays all-null — clicks are routed by slot index.
    private final UUID[] entryIds;

    public DeathHistoryListMenu(int id, Inventory playerInv, List<DeathHistoryEntry> entries) {
        super(PGMenus.HISTORY_LIST.get(), id);
        this.entryIds = new UUID[DISPLAY_SIZE];

        Container display = new SimpleContainer(DISPLAY_SIZE);
        for (int i = 0; i < DISPLAY_SIZE && i < entries.size(); i++) {
            DeathHistoryEntry e = entries.get(i);
            display.setItem(i, buildEntryCard(e));
            entryIds[i] = e.id();
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

    public static DeathHistoryListMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        return new DeathHistoryListMenu(id, inv, List.of());
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < DISPLAY_SIZE
            && clickType == ClickType.PICKUP
            && player instanceof ServerPlayer sp) {

            UUID entryId = entryIds[slotId];
            if (entryId != null) {
                DeathHistoryData data = DeathHistoryData.get(sp.getServer());
                data.findById(entryId).ifPresent(entry -> {
                    sp.closeContainer();
                    sp.openMenu(detailProvider(entry));
                });
                return;
            }
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

    private static MenuProvider detailProvider(DeathHistoryEntry entry) {
        return new MenuProvider() {
            @Override public Component getDisplayName() {
                String date = DATE_FMT.format(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(entry.timestampMillis()), ZoneId.systemDefault()));
                return Component.translatable("perfectgraves.history.detail_title", date);
            }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                return new DeathHistoryDetailMenu(id, inv, entry);
            }
        };
    }

    private static ItemStack buildEntryCard(DeathHistoryEntry entry) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = head.getOrCreateTag();

        CompoundTag owner = new CompoundTag();
        owner.putUUID("Id", entry.ownerId());
        owner.putString("Name", entry.ownerName());
        tag.put("SkullOwner", owner);

        head.setHoverName(Component.translatable("perfectgraves.history.list.card_title",
            entry.ownerName()).withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)));

        String date = DATE_FMT.format(LocalDateTime.ofInstant(
            Instant.ofEpochMilli(entry.timestampMillis()), ZoneId.systemDefault()));
        String pos = entry.deathPos().getX() + ", " + entry.deathPos().getY() + ", " + entry.deathPos().getZ();
        Component dimName = DimensionDisplay.name(entry.dimension());

        ListTag lore = new ListTag();
        addLore(lore, Component.literal(date).withStyle(ChatFormatting.GRAY));
        // Pass the cause Component itself so any embedded translatable keys resolve in the
        // viewer's locale on the client. getString() here would resolve to English on the
        // server (no Language registry) and bake that into a literal.
        Component cause = entry.causeComponent();
        if (!cause.getString().isEmpty()) {
            addLore(lore, Component.translatable("perfectgraves.history.lore.cause",
                cause.copy().withStyle(ChatFormatting.YELLOW))
                .withStyle(ChatFormatting.GRAY));
        }
        addLore(lore, Component.translatable("perfectgraves.history.lore.position",
            dimName, Component.literal(pos)).withStyle(ChatFormatting.DARK_GRAY));
        addLore(lore, Component.translatable("perfectgraves.history.lore.items_xp",
            entry.items().size(), entry.xp()).withStyle(ChatFormatting.GRAY));
        addLore(lore, Component.translatable(outcomeKey(entry.outcome()))
            .withStyle(outcomeColor(entry.outcome())));
        addLore(lore, Component.translatable("perfectgraves.history.lore.click_to_view")
            .withStyle(ChatFormatting.AQUA));

        tag.getCompound("display").put("Lore", lore);
        return head;
    }

    private static void addLore(ListTag lore, Component line) {
        lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
    }

    private static String outcomeKey(CascadeResult outcome) {
        return switch (outcome) {
            case GRAVE -> "perfectgraves.history.lore.outcome_grave";
            case VIRTUAL_GRAVE -> "perfectgraves.history.lore.outcome_virtual";
            case SAFE_DROP -> "perfectgraves.history.lore.outcome_safedrop";
            case VANILLA_DROP -> "perfectgraves.history.lore.outcome_vanilla";
            case RETURN_TO_INVENTORY -> "perfectgraves.history.lore.outcome_virtual";
        };
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

    // Used by the detail menu's back-button to reopen this list. Centralized here so we don't
    // duplicate the per-owner fetch + sort logic.
    public static MenuProvider listProviderFor(java.util.UUID ownerId, String ownerName,
                                               net.minecraft.server.MinecraftServer server) {
        List<DeathHistoryEntry> entries = new ArrayList<>(DeathHistoryData.get(server).getFor(ownerId));
        return new MenuProvider() {
            @Override public Component getDisplayName() {
                return Component.translatable("perfectgraves.history.title", ownerName);
            }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                return new DeathHistoryListMenu(id, inv, entries);
            }
        };
    }
}
