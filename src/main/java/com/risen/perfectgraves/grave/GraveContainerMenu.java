package com.risen.perfectgraves.grave;

import com.risen.perfectgraves.registry.PGBlocks;
import com.risen.perfectgraves.registry.PGMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class GraveContainerMenu extends AbstractContainerMenu {

    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int GRAVE_SLOT_COUNT = COLS * ROWS;

    public static final int BUTTON_TAKE_XP = 0;

    private final Container grave;
    // Non-null on server (real BE), null on client (proxy). The DataSlot below gets/sets xp
    // through this ref so the client can't fabricate amounts — server is authoritative.
    @Nullable private final GraveBlockEntity graveBE;
    // Client-side cache of the synced xp value; written by DataSlot.set during sync broadcast.
    private int syncedXp;

    // Server-side constructor: wraps the real GraveBlockEntity and wires XP sync.
    public GraveContainerMenu(int id, Inventory playerInv, Container grave, @Nullable GraveBlockEntity be) {
        super(PGMenus.GRAVE.get(), id);
        this.grave = grave;
        this.graveBE = be;
        addGraveSlots(grave);
        addPlayerSlots(playerInv);
        addDataSlot(new DataSlot() {
            @Override public int get() { return graveBE != null ? graveBE.getXp() : syncedXp; }
            @Override public void set(int v) { syncedXp = v; }
        });
    }

    // Legacy 3-arg ctor kept for any external callers; delegates with null BE.
    public GraveContainerMenu(int id, Inventory playerInv, Container grave) {
        this(id, playerInv, grave, null);
    }

    // Client-side factory used by IForgeMenuType.create. The client gets a fixed-size proxy
    // container matching the 54-slot layout; actual item contents sync through the menu's
    // slot-update packets. XP value arrives via DataSlot sync. No extra ByteBuf data needed.
    public static GraveContainerMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        return new GraveContainerMenu(id, inv, new SimpleContainer(GRAVE_SLOT_COUNT), null);
    }

    public int getSyncedXp() {
        return graveBE != null ? graveBE.getXp() : syncedXp;
    }

    // Server-authoritative: validates access, drains xp atomically via takeXp(), awards to player.
    // Rapid clicks are safe — takeXp() returns 0 once the BE is empty so subsequent calls no-op.
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != BUTTON_TAKE_XP) return false;
        if (graveBE == null) return false;
        if (!graveBE.canAccess(player)) return false;
        if (!(player instanceof ServerPlayer sp)) return false;
        int amount = graveBE.takeXp();
        if (amount <= 0) return false;
        sp.giveExperiencePoints(amount);
        return true;
    }

    // When the player closes the menu, if the grave has been fully drained (no items, no xp),
    // remove the block — no reason to leave an empty grave sitting around. Runs server-side only.
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (graveBE == null || player.level().isClientSide) return;
        if (!grave.isEmpty()) return;
        if (graveBE.getXp() > 0) return;
        var level = graveBE.getLevel();
        if (level == null) return;
        var pos = graveBE.getBlockPos();
        if (level.getBlockState(pos).is(PGBlocks.GRAVE.get())) {
            level.removeBlock(pos, false);
        }
    }

    private void addGraveSlots(Container c) {
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col;
                if (idx >= c.getContainerSize()) {
                    // Pad with inert slots so the layout stays rectangular; these never hold items.
                    this.addSlot(new InertSlot(8 + col * 18, 18 + row * 18));
                } else {
                    this.addSlot(new ReadOnlySlot(c, idx, 8 + col * 18, 18 + row * 18));
                }
            }
        }
    }

    private void addPlayerSlots(Inventory inv) {
        int invY = 18 + ROWS * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inv, col, 8 + col * 18, invY + 58));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return grave.stillValid(player);
    }

    // Shift-click only moves items grave -> player. Never the other direction (read-only).
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return moved;

        ItemStack stack = slot.getItem();
        moved = stack.copy();

        if (index < GRAVE_SLOT_COUNT) {
            if (!this.moveItemStackTo(stack, GRAVE_SLOT_COUNT, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Player -> grave is disallowed.
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return moved;
    }

    public int getGraveContainerSize() {
        return grave.getContainerSize();
    }

    private static class ReadOnlySlot extends Slot {
        ReadOnlySlot(Container c, int slot, int x, int y) {
            super(c, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    private static class InertSlot extends Slot {
        InertSlot(int x, int y) {
            super(new SimpleContainer(1), 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) { return false; }

        @Override
        public boolean mayPickup(Player player) { return false; }

        @Override
        public boolean isActive() { return false; }
    }
}
