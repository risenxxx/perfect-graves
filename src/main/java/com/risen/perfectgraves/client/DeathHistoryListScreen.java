package com.risen.perfectgraves.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.risen.perfectgraves.history.DeathHistoryListMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

// 3-row chest-style list, mirrors VirtualGraveScreen layout — same blit math, just bound to
// the history menu type.
public class DeathHistoryListScreen extends AbstractContainerScreen<DeathHistoryListMenu> {

    private static final ResourceLocation BG =
        new ResourceLocation("minecraft:textures/gui/container/generic_54.png");

    public DeathHistoryListScreen(DeathHistoryListMenu menu, Inventory inv, Component title) {
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
        g.blit(BG, x, y, 0, 0, this.imageWidth, 17 + 3 * 18);
        g.blit(BG, x, y + 17 + 3 * 18, 0, 126, this.imageWidth, 96);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
