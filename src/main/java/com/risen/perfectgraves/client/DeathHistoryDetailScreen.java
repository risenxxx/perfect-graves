package com.risen.perfectgraves.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.risen.perfectgraves.history.DeathHistoryDetailMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

// 6-row chest layout: row 0 holds the back/info/overflow items, rows 1-5 show the snapshotted
// inventory. Same generic_54 texture as VirtualGraveScreen — vanilla supports up to 6 chest
// rows in this texture natively.
public class DeathHistoryDetailScreen extends AbstractContainerScreen<DeathHistoryDetailMenu> {

    private static final ResourceLocation BG =
        new ResourceLocation("minecraft:textures/gui/container/generic_54.png");

    private static final int CHEST_ROWS = 6;

    public DeathHistoryDetailScreen(DeathHistoryDetailMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 18 + CHEST_ROWS * 18 + 14 + 3 * 18 + 4 + 18 + 7;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // Top: title + 6 rows of chest slots.
        g.blit(BG, x, y, 0, 0, this.imageWidth, 17 + CHEST_ROWS * 18);
        // Bottom: player inventory section, source y=126 in the texture.
        g.blit(BG, x, y + 17 + CHEST_ROWS * 18, 0, 126, this.imageWidth, 96);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
