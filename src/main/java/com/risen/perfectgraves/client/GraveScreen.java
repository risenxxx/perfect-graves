package com.risen.perfectgraves.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.risen.perfectgraves.grave.GraveContainerMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class GraveScreen extends AbstractContainerScreen<GraveContainerMenu> {

    // Vanilla's 6-row chest texture. Shares layout with our 6x9 grave grid + player inventory.
    private static final ResourceLocation BG =
        new ResourceLocation("minecraft:textures/gui/container/generic_54.png");

    private static final int XP_BUTTON_W = 72;
    private static final int XP_BUTTON_H = 20;

    private Button xpButton;

    public GraveScreen(GraveContainerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        // Place the XP button to the right of the chest background, aligned with the top edge.
        this.xpButton = Button.builder(xpButtonLabel(menu.getSyncedXp()), b -> onTakeXp())
            .bounds(this.leftPos + this.imageWidth + 4, this.topPos + 6, XP_BUTTON_W, XP_BUTTON_H)
            .build();
        this.addRenderableWidget(this.xpButton);
        this.xpButton.active = menu.getSyncedXp() > 0;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int xp = menu.getSyncedXp();
        this.xpButton.active = xp > 0;
        this.xpButton.setMessage(xpButtonLabel(xp));
    }

    private void onTakeXp() {
        if (this.minecraft == null || this.minecraft.gameMode == null) return;
        if (menu.getSyncedXp() <= 0) return;
        this.minecraft.gameMode.handleInventoryButtonClick(menu.containerId, GraveContainerMenu.BUTTON_TAKE_XP);
    }

    private static Component xpButtonLabel(int xp) {
        if (xp <= 0) return Component.translatable("perfectgraves.menu.take_xp.empty");
        return Component.translatable("perfectgraves.menu.take_xp", formatXpAsLevels(xp));
    }

    // Same curve as the grave-head hologram — see GraveBlockEntityRenderer.formatXpAsLevels.
    // Kept local here to avoid dragging client render code into the menu screen package graph.
    private static String formatXpAsLevels(int xpPoints) {
        if (xpPoints <= 0) return "0 lvl";
        int remaining = xpPoints;
        int level = 0;
        while (remaining > 0) {
            int cost = xpToNextLevel(level);
            if (remaining < cost) {
                float frac = (float) remaining / (float) cost;
                float lvl = level + frac;
                if (lvl < 0.05f) return xpPoints + " xp";
                return String.format("%.1f lvl", lvl);
            }
            remaining -= cost;
            level++;
        }
        return level + " lvl";
    }

    private static int xpToNextLevel(int level) {
        if (level < 16) return 2 * level + 7;
        if (level < 31) return 5 * level - 38;
        return 9 * level - 158;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // generic_54.png is 256x256; source rect is the 6-row chest body.
        g.blit(BG, x, y, 0, 0, this.imageWidth, 6 * 18 + 17);
        g.blit(BG, x, y + 6 * 18 + 17, 0, 126, this.imageWidth, 96);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
