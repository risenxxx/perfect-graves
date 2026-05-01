package com.risen.perfectgraves.virtual;

import com.risen.perfectgraves.grave.QuickPickup;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// /grave list opens this. Each cell is a PLAYER_HEAD "card" showing owner + death date +
// item/xp count. Clicking a card triggers the same restore path as /grave restore <id>.
public class VirtualGraveMenu extends AbstractContainerMenu {

    private static final int COLS = 9;
    private static final int ROWS = 3;
    private static final int DISPLAY_SIZE = COLS * ROWS;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // Server-side: graveIds[slot] tells us which VG a clicked slot maps to. Null = empty slot.
    // Client-side: this list is null (filled with nulls) — clicks are routed by slot index, and the
    // server does the lookup using its own populated list.
    private final UUID[] graveIds;

    public VirtualGraveMenu(int id, Inventory playerInv, List<VirtualGrave> graves) {
        // Default constructor — opener owns the graves. Used by /grave list (self) and the
        // network-side proxy on the client (where the lore is populated server-side and slot-synced
        // anyway, so the flag here doesn't actually drive any rendering on this side).
        this(id, playerInv, graves, true);
    }

    public VirtualGraveMenu(int id, Inventory playerInv, List<VirtualGrave> graves, boolean isSelfView) {
        super(PGMenus.VIRTUAL_GRAVE.get(), id);
        this.graveIds = new UUID[DISPLAY_SIZE];

        Container display = new SimpleContainer(DISPLAY_SIZE);
        for (int i = 0; i < DISPLAY_SIZE && i < graves.size(); i++) {
            VirtualGrave vg = graves.get(i);
            display.setItem(i, buildGraveCard(vg, isSelfView));
            graveIds[i] = vg.id();
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

    public static VirtualGraveMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        // Client proxy: display items sync via normal slot-update packets. graveIds stays all-null
        // on the client; only the server routes clicks to real VGs.
        return new VirtualGraveMenu(id, inv, List.of());
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < DISPLAY_SIZE
            && clickType == ClickType.PICKUP
            && player instanceof ServerPlayer sp) {

            UUID graveId = graveIds[slotId];
            if (graveId == null) {
                return; // empty slot — ignore
            }

            VirtualGraveData data = VirtualGraveData.get(sp.getServer());
            Optional<VirtualGrave> vgOpt = data.find(graveId);
            if (vgOpt.isEmpty()) {
                return; // grave already gone (concurrent click / eviction)
            }
            VirtualGrave vg = vgOpt.get();

            // dragType: 0 = left, 1 = right. For self-graves both clicks behave identically
            // (restore to opener). For OPs viewing someone else's graves, right-click delivers
            // to the original owner if they're online.
            boolean isOwner = sp.getUUID().equals(vg.ownerId());
            boolean rightClick = dragType == 1;

            if (isOwner || !rightClick) {
                data.remove(graveId);
                sp.closeContainer();
                QuickPickup.restoreVirtual(sp, vg);
                return;
            }

            // OP right-click → deliver to owner.
            ServerPlayer ownerPlayer = sp.getServer().getPlayerList().getPlayer(vg.ownerId());
            if (ownerPlayer == null) {
                // Don't consume the grave — OP can retry when the owner is online.
                sp.displayClientMessage(Component.translatable(
                    "perfectgraves.virtual.restore.owner_offline", vg.ownerName())
                    .withStyle(net.minecraft.ChatFormatting.RED), false);
                return;
            }

            data.remove(graveId);
            sp.closeContainer();
            QuickPickup.restoreVirtual(ownerPlayer, vg);

            ownerPlayer.displayClientMessage(Component.translatable(
                "perfectgraves.virtual.restore.received", sp.getGameProfile().getName())
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);
            sp.displayClientMessage(Component.translatable(
                "perfectgraves.virtual.restore.delivered", ownerPlayer.getGameProfile().getName())
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);
            return;
        }
        // Non-display slots: let vanilla handle so players can still rearrange their inventory.
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

    private static ItemStack buildGraveCard(VirtualGrave vg, boolean isSelfView) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = head.getOrCreateTag();

        CompoundTag owner = new CompoundTag();
        owner.putUUID("Id", vg.ownerId());
        owner.putString("Name", vg.ownerName());
        tag.put("SkullOwner", owner);

        head.setHoverName(Component.translatable("container.perfectgraves.grave_of", vg.ownerName())
            .withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(false)));

        String date = DATE_FMT.format(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(vg.timestampMillis()), ZoneId.systemDefault()));
        String pos = vg.deathPos().getX() + ", " + vg.deathPos().getY() + ", " + vg.deathPos().getZ();
        Component dimName = DimensionDisplay.name(vg.dimension());

        ListTag lore = new ListTag();
        addLore(lore, Component.literal(date).withStyle(ChatFormatting.GRAY));
        // Pass the cause Component itself as the %s arg so the client resolves any translatable
        // keys in its OWN locale. Calling getString() here would resolve on the server (no
        // Language registry → English fallback), then bake the result into a literal — which
        // would force English regardless of client locale. The empty-check uses getString() only
        // as a presence test; the rendered text comes from the Component.
        Component cause = vg.causeComponent();
        if (!cause.getString().isEmpty()) {
            addLore(lore, Component.translatable("perfectgraves.history.lore.cause",
                cause.copy().withStyle(ChatFormatting.YELLOW))
                .withStyle(ChatFormatting.GRAY));
        }
        addLore(lore, Component.translatable("perfectgraves.history.lore.position",
            dimName, Component.literal(pos)).withStyle(ChatFormatting.DARK_GRAY));
        addLore(lore, Component.translatable("perfectgraves.history.lore.items_xp",
            vg.items().size(), vg.xp()).withStyle(ChatFormatting.GRAY));
        if (isSelfView) {
            addLore(lore, Component.translatable("perfectgraves.virtual.lore.click_to_restore")
                .withStyle(ChatFormatting.AQUA));
        } else {
            // OP-view: surface the dual-click action explicitly so the right-click delivery path
            // is discoverable. Aqua keeps the "primary action" reading consistent with the self
            // view; gold differentiates the OP-only delivery action.
            addLore(lore, Component.translatable("perfectgraves.virtual.lore.click_left_take")
                .withStyle(ChatFormatting.AQUA));
            addLore(lore, Component.translatable("perfectgraves.virtual.lore.click_right_deliver")
                .withStyle(ChatFormatting.GOLD));
        }
        tag.getCompound("display").put("Lore", lore);
        return head;
    }

    private static void addLore(ListTag lore, Component line) {
        lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
    }

    private static class DisplaySlot extends Slot {
        DisplaySlot(Container c, int slot, int x, int y) {
            super(c, slot, x, y);
        }

        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }
}
