package com.risen.perfectgraves.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.risen.perfectgraves.grave.GraveBlockEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GraveBlockEntityRenderer implements BlockEntityRenderer<GraveBlockEntity> {

    private static final double HOLOGRAM_VISIBLE_DIST_SQ = 16.0 * 16.0;
    // Head is ~0.5 blocks tall in FIXED context with model origin at its center.
    // Y=0.083 puts the center at H/6 → bottom at -H/3 (1/3 sunk into the block below),
    // top at +2H/3 above ground. Tune in-game if the vanilla item transform differs.
    private static final float HEAD_Y_OFFSET = 0.08f;
    private static final float HEAD_SCALE = 1.0f;
    // Static tilted pose: yaw aims the face at a block corner, pitch tips it upward so the
    // player sees the face when walking up to the grave, roll adds a slight cocked-to-the-side
    // look so the head doesn't feel mechanically aligned.
    private static final float HEAD_YAW_DEG = 45f;
    private static final float HEAD_PITCH_DEG = 20f;
    private static final float HEAD_ROLL_DEG = 8f;
    private static final float HOLOGRAM_Y_OFFSET = 1.3f;
    private static final float HOLOGRAM_SCALE = 0.025f;
    // Distance (blocks) below which we start shrinking the hologram's world size so the apparent
    // screen size doesn't grow without bound when the camera is right next to the grave. At
    // distances ≥ this threshold the hologram renders at full HOLOGRAM_SCALE.
    private static final float HOLOGRAM_NEAR_DIST = 5.0f;
    // Floor for the close-range scale factor. At very close distances the hologram still renders
    // at MIN_SCALE_FACTOR × HOLOGRAM_SCALE so it doesn't vanish entirely if you walk into it.
    private static final float HOLOGRAM_MIN_SCALE_FACTOR = 0.3f;
    // ARGB. 0xE0 alpha ≈ 88% — opaque enough that block colors don't bleed through noticeably
    // at close range while still feeling hologram-y.
    private static final int HOLOGRAM_BG = 0xE0000000;
    // Horizontal + vertical padding inside the background rect, in font pixels.
    private static final int HOLOGRAM_PAD_X = 3;
    private static final int HOLOGRAM_PAD_Y = 2;

    // Color palette (ARGB). Split here so future template customization can swap these cheaply.
    private static final int COLOR_GRAVE_OF = 0xFFFFAA00;  // gold — "Grave of"
    private static final int COLOR_OWNER    = 0xFFFFFF55;  // yellow — owner name
    private static final int COLOR_KILLED   = 0xFFFFFF55;  // yellow — "<name> was killed"
    private static final int COLOR_LABEL    = 0xFFAAAAAA;  // gray — "Items:", "XP:"
    private static final int COLOR_VALUE    = 0xFFFFFFFF;  // white — numbers + durations
    private static final int COLOR_PROTECT  = 0xFF5555FF;  // blue — "Protected for"
    private static final int COLOR_DESPAWN  = 0xFFFF5555;  // red — "Breaks in"

    private final BlockEntityRendererProvider.Context ctx;
    private final Font font;
    private final Map<UUID, ItemStack> headCache = new HashMap<>();

    public GraveBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
        this.ctx = ctx;
        this.font = ctx.getFont();
    }

    @Override
    public void render(GraveBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buf, int packedLight, int packedOverlay) {
        GameProfile owner = be.getOwner();
        if (owner == null) return;

        // Hologram rendering moved to GraveHologramRenderer (post-translucent stage) so water and
        // other translucent blocks don't paint over it. The BER keeps only the head render.
        renderHead(be, owner, pose, buf, packedLight, packedOverlay);
    }

    private void renderHead(GraveBlockEntity be, GameProfile owner, PoseStack pose,
                            MultiBufferSource buf, int packedLight, int packedOverlay) {
        ItemStack head = getHeadStack(owner);

        pose.pushPose();
        pose.translate(0.5, HEAD_Y_OFFSET, 0.5);
        // Yaw first (face toward corner), then pitch (tip upward), then roll (slight side tilt).
        pose.mulPose(Axis.YP.rotationDegrees(HEAD_YAW_DEG));
        pose.mulPose(Axis.XP.rotationDegrees(HEAD_PITCH_DEG));
        pose.mulPose(Axis.ZP.rotationDegrees(HEAD_ROLL_DEG));
        pose.scale(HEAD_SCALE, HEAD_SCALE, HEAD_SCALE);
        Minecraft.getInstance().getItemRenderer().renderStatic(
            head,
            ItemDisplayContext.FIXED,
            packedLight,
            packedOverlay,
            pose,
            buf,
            be.getLevel(),
            0
        );
        pose.popPose();
    }

    // Called from both the BER (when inlined) and the post-translucent stage handler.
    // Expects `pose` to be in the same coordinate frame a BER would see: origin at the grave's
    // block corner, world-axis-aligned. The stage handler translates from camera to that frame
    // before calling.
    public static void renderHologram(GraveBlockEntity be, PoseStack pose, MultiBufferSource buf, Camera camera) {
        Font font = Minecraft.getInstance().font;
        List<Component> lines = buildHologramLines(be, font);
        if (lines.isEmpty()) return;

        // Distance-based scale clamp: the apparent screen size of the hologram is proportional
        // to (worldSize / distance), so when distance shrinks the hologram normally grows. To
        // cap that growth we shrink worldSize linearly with distance below NEAR_DIST. Net
        // result: at dist ≥ NEAR_DIST the hologram is rendered at full HOLOGRAM_SCALE; at
        // dist < NEAR_DIST it shrinks so its apparent size never exceeds what it would be at
        // exactly NEAR_DIST. Floored so it doesn't disappear if the camera is inside it.
        BlockPos bp = be.getBlockPos();
        Vec3 camPos = camera.getPosition();
        double dx = bp.getX() + 0.5 - camPos.x;
        double dy = bp.getY() + HOLOGRAM_Y_OFFSET - camPos.y;
        double dz = bp.getZ() + 0.5 - camPos.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float scaleFactor = (float) Math.max(HOLOGRAM_MIN_SCALE_FACTOR,
            Math.min(1.0, dist / HOLOGRAM_NEAR_DIST));
        float scale = HOLOGRAM_SCALE * scaleFactor;

        pose.pushPose();
        pose.translate(0.5, HOLOGRAM_Y_OFFSET, 0.5);
        pose.mulPose(camera.rotation());
        pose.scale(-scale, -scale, scale);

        Matrix4f matrix = pose.last().pose();
        int fullBright = LightTexture.pack(15, 15);

        // Compute block size so we can draw ONE unified background rect (Universal Graves style)
        // instead of vanilla's per-line boxes.
        int maxWidth = 0;
        for (Component line : lines) {
            int w = font.width(line);
            if (w > maxWidth) maxWidth = w;
        }
        int totalHeight = lines.size() * font.lineHeight;
        float halfW = maxWidth / 2f;
        float halfH = totalHeight / 2f;

        drawBackgroundRect(
            matrix, buf, fullBright,
            -halfW - HOLOGRAM_PAD_X, -halfH - HOLOGRAM_PAD_Y,
             halfW + HOLOGRAM_PAD_X,  halfH + HOLOGRAM_PAD_Y,
            RenderType.textBackgroundSeeThrough(), HOLOGRAM_BG
        );

        float y = -halfH;
        for (Component line : lines) {
            float x = -font.width(line) / 2f;
            font.drawInBatch(line, x, y, 0xFFFFFFFF, false, matrix, buf,
                Font.DisplayMode.SEE_THROUGH, 0, fullBright);
            y += font.lineHeight;
        }
        pose.popPose();
    }

    // Unified semi-transparent rect behind all lines. The caller picks the render type so the
    // same vertex layout serves both the see-through ghost pass and the depth-tested solid pass.
    private static void drawBackgroundRect(Matrix4f m, MultiBufferSource buf, int light,
                                           float x1, float y1, float x2, float y2,
                                           RenderType type, int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>>  8) & 0xFF) / 255f;
        float b = ( argb         & 0xFF) / 255f;
        VertexConsumer vc = buf.getBuffer(type);
        vc.vertex(m, x1, y1, 0f).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x1, y2, 0f).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x2, y2, 0f).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x2, y1, 0f).color(r, g, b, a).uv2(light).endVertex();
    }

    private static List<Component> buildHologramLines(GraveBlockEntity be, Font font) {
        List<Component> lines = new ArrayList<>();

        GameProfile owner = be.getOwner();
        String name = owner != null ? owner.getName() : null;
        if (name != null) {
            Component ownerPart = Component.literal(name).withStyle(styled(COLOR_OWNER));
            // "Grave of %s" — outer style gold, %s in yellow
            lines.add(Component.translatable("perfectgraves.hologram.grave_of", ownerPart)
                .withStyle(styled(COLOR_GRAVE_OF)));
            // Second line: vanilla-formatted death message ("<name> fell from a high place",
            // "<name> was slain by Zombie", etc.) — captured at death time. Falls back to the
            // static "<name> was killed" template for legacy saves where deathMessage is null.
            Component killedLine;
            Component cached = be.getDeathMessage();
            if (cached != null) {
                killedLine = cached.copy().withStyle(styled(COLOR_KILLED));
            } else {
                killedLine = Component.translatable("perfectgraves.hologram.killed",
                    Component.literal(name)).withStyle(styled(COLOR_KILLED));
            }
            lines.add(killedLine);
            // Blank spacer between the name block and the stats block.
            lines.add(Component.empty());
        }

        // "Items: %s  XP: %s" — gray labels (outer style), white values (arg styles).
        int itemCount = countNonEmpty(be);
        int xp = be.getXp();
        lines.add(Component.translatable(
            "perfectgraves.hologram.items_xp",
            Component.literal(Integer.toString(itemCount)).withStyle(styled(COLOR_VALUE)),
            Component.literal(formatXpAsLevels(xp)).withStyle(styled(COLOR_VALUE))
        ).withStyle(styled(COLOR_LABEL)));

        // "Protected for %s" — blue label, white time.
        // Picks formula based on the server-authoritative `protectionPaused` flag (set in
        // saveAdditional → synced via getUpdateTag). When unpaused — owner online, OR
        // pauseProtectionWhileOffline=false — extrapolate `endsAt − gameTime` for a smooth
        // per-frame countdown between the 10s server syncs. When paused, the server has frozen
        // the counter and keeps re-sending the same `onlineTicksElapsed` each sync, so
        // `total − elapsed` gives a frozen "Protected for Xm Ys" display, matching the actual
        // paused state instead of the sawtooth gameTime-extrapolation would produce.
        if (!be.isProtectionExpired() && be.getLevel() != null && be.getProtectionTotalTicks() > 0) {
            long remainingTicks;
            if (be.isProtectionPaused()) {
                remainingTicks = Math.max(0L,
                    be.getProtectionTotalTicks() - be.getOnlineTicksElapsed());
            } else {
                remainingTicks = Math.max(0L,
                    be.getProtectionEndsAtGameTime() - be.getLevel().getGameTime());
            }
            if (remainingTicks > 0) {
                lines.add(Component.translatable(
                    "perfectgraves.hologram.protected",
                    Component.literal(formatDuration(remainingTicks / 20L)).withStyle(styled(COLOR_VALUE))
                ).withStyle(styled(COLOR_PROTECT)));
            }
        }

        // "Breaks in %s" — red label, white time. Real-time (does not pause).
        long selfDestructAt = be.getSelfDestructAt();
        if (selfDestructAt > 0L && be.getLevel() != null) {
            long remaining = selfDestructAt - be.getLevel().getGameTime();
            if (remaining > 0L) {
                lines.add(Component.translatable(
                    "perfectgraves.hologram.despawn",
                    Component.literal(formatDuration(remaining / 20L)).withStyle(styled(COLOR_VALUE))
                ).withStyle(styled(COLOR_DESPAWN)));
            }
        }

        return lines;
    }

    private static Style styled(int argb) {
        // Font colors in vanilla are 24-bit RGB — alpha channel is ignored by the font shader.
        // Masking out alpha keeps the intent clear and avoids surprises if someone feeds this
        // value to a path that does respect alpha.
        return Style.EMPTY.withColor(argb & 0x00FFFFFF);
    }

    private static int countNonEmpty(GraveBlockEntity be) {
        int count = 0;
        for (int i = 0; i < be.getContainerSize(); i++) {
            if (!be.getItem(i).isEmpty()) count++;
        }
        return count;
    }

    public static boolean isWithinHologramRange(Camera camera, BlockPos pos) {
        Vec3 cam = camera.getPosition();
        double dx = pos.getX() + 0.5 - cam.x;
        double dy = pos.getY() + 0.5 - cam.y;
        double dz = pos.getZ() + 0.5 - cam.z;
        return dx * dx + dy * dy + dz * dz <= HOLOGRAM_VISIBLE_DIST_SQ;
    }

    // Vanilla XP-to-level curve (LevelExperienceMap):
    //   level L → L+1 costs 2L+7  for L < 16
    //                      5L-38  for 16 <= L < 31
    //                      9L-158 for L >= 31
    // We walk level-by-level subtracting until we can't complete the next level; the remainder
    // becomes the fractional part. Shown with one decimal; sub-0.05 values show as "0" to avoid
    // a misleading "0.0 lvl" when there's basically no XP. Raw point count appended for players
    // who want the precise number.
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
                return String.format("%.1f lvl (%d xp)", lvl, xpPoints);
            }
            remaining -= cost;
            level++;
        }
        return String.format("%d lvl (%d xp)", level, xpPoints);
    }

    private static int xpToNextLevel(int level) {
        if (level < 16) return 2 * level + 7;
        if (level < 31) return 5 * level - 38;
        return 9 * level - 158;
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60L) return seconds + "s";
        long m = seconds / 60L;
        long s = seconds % 60L;
        if (m < 60L) return String.format("%dm %02ds", m, s);
        long h = m / 60L;
        m %= 60L;
        return String.format("%dh %02dm", h, m);
    }

    private ItemStack getHeadStack(GameProfile owner) {
        UUID id = owner.getId();
        if (id == null) {
            return buildHeadStack(owner);
        }
        return headCache.computeIfAbsent(id, k -> buildHeadStack(owner));
    }

    private static ItemStack buildHeadStack(GameProfile owner) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag skullOwner = new CompoundTag();
        NbtUtils.writeGameProfile(skullOwner, owner);
        stack.getOrCreateTag().put("SkullOwner", skullOwner);
        return stack;
    }
}
