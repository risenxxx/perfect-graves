package com.risen.perfectgraves.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.risen.perfectgraves.marker.LooseDropMarkerEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

// No-op renderer. The LooseDropMarkerEntity has no in-world visual of its own — the long-distance
// marker is drawn by GraveHologramRenderer reading ClientLooseDropCache. We register a renderer
// only because Minecraft requires every EntityType to have one or the client crashes when the
// entity arrives.
public final class LooseDropMarkerRenderer extends EntityRenderer<LooseDropMarkerEntity> {

    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/misc/white.png");

    public LooseDropMarkerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
    }

    @Override
    public void render(LooseDropMarkerEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buf, int packedLight) {
        // Intentionally empty.
    }

    @Override
    public ResourceLocation getTextureLocation(LooseDropMarkerEntity entity) {
        return TEXTURE;
    }
}
