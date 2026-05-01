package com.risen.perfectgraves.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.risen.perfectgraves.virtual.VirtualGraveMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class VirtualGraveScreen extends AbstractContainerScreen<VirtualGraveMenu> {

    // 6-row chest texture — we only blit the top 3 rows of slots + player inventory.
    private static final ResourceLocation BG =
        new ResourceLocation("minecraft:textures/gui/container/generic_54.png");

    public VirtualGraveScreen(VirtualGraveMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 18 + 3 * 18 + 14 + 3 * 18 + 4 + 18 + 7;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // Top 3 rows of slots: y=0..17+3*18 = 0..71
        g.blit(BG, x, y, 0, 0, this.imageWidth, 17 + 3 * 18);
        // Player inv section: source y=126 (matches vanilla layout for dynamic row counts)
        g.blit(BG, x, y + 17 + 3 * 18, 0, 126, this.imageWidth, 96);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
