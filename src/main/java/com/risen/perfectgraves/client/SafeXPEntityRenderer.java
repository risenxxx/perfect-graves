package com.risen.perfectgraves.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.risen.perfectgraves.xp.SafeXPEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

// Lightweight billboard renderer modelled on vanilla ExperienceOrbRenderer but without the
// per-tier icon lookup — a SafeXPEntity represents a single consolidated orb so we just use
// the largest tier (2477+ xp) icon at a slightly larger scale than vanilla for visual weight.
public class SafeXPEntityRenderer extends EntityRenderer<SafeXPEntity> {

    private static final ResourceLocation TEXTURE =
        new ResourceLocation("minecraft:textures/entity/experience_orb.png");
    private static final RenderType RENDER_TYPE = RenderType.itemEntityTranslucentCull(TEXTURE);

    // Tier 10 icon: texture is 64x64 with 16px-per-tier grid; tier 10 = col 2, row 2.
    private static final float U0 = 32f / 64f;
    private static final float U1 = 48f / 64f;
    private static final float V0 = 32f / 64f;
    private static final float V1 = 48f / 64f;

    public SafeXPEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.15f;
        this.shadowStrength = 0.75f;
    }

    @Override
    public void render(SafeXPEntity e, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buf, int packedLight) {
        pose.pushPose();
        pose.translate(0, 0.1, 0);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        float s = 0.5f; // slightly bigger than vanilla 0.3 to read as "bigger prize"
        pose.scale(s, s, s);

        VertexConsumer vc = buf.getBuffer(RENDER_TYPE);
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        Matrix3f n = last.normal();
        vertex(vc, m, n, U0, V1, -0.5f, -0.25f, packedLight);
        vertex(vc, m, n, U1, V1,  0.5f, -0.25f, packedLight);
        vertex(vc, m, n, U1, V0,  0.5f,  0.75f, packedLight);
        vertex(vc, m, n, U0, V0, -0.5f,  0.75f, packedLight);

        pose.popPose();
        super.render(e, entityYaw, partialTick, pose, buf, packedLight);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n,
                               float u, float v, float x, float y, int light) {
        vc.vertex(m, x, y, 0)
          .color(255, 255, 255, 255)
          .uv(u, v)
          .overlayCoords(OverlayTexture.NO_OVERLAY)
          .uv2(light)
          .normal(n, 0, 1, 0)
          .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(SafeXPEntity e) {
        return TEXTURE;
    }
}
