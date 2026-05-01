package com.risen.perfectgraves.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.grave.GraveBlockEntity;
import com.risen.perfectgraves.marker.LooseDropMarkerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

// Renders grave holograms AFTER translucent blocks so water and glass don't paint over them.
// The BER itself runs during the block-entity phase (earlier than translucent) and writes no
// depth for SEE_THROUGH text; water, drawn later, otherwise alpha-blends over the already-drawn
// text. Moving the hologram to AFTER_TRANSLUCENT_BLOCKS makes ordering come out right without
// changing any depth state.
@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID, value = Dist.CLIENT)
public final class GraveHologramRenderer {

    // Mod IDs that ship in-world death markers (beam + label) — duplicating their work would
    // just be visual noise. 2D-only mods (VoxelMap, AdvancedCompass) aren't included because
    // their minimap dot doesn't conflict with our 3D marker; they complement each other.
    private static final String[] CONFLICTING_MARKER_MOD_IDS = {
        "xaerominimap", "journeymap", "ftbchunks"
    };

    // Computed once at first marker draw, since the loaded-mod list doesn't change at runtime.
    private static volatile Boolean cachedConflictingModLoaded = null;

    // Distance band where we crossfade between hologram (close-range, full info) and marker
    // (long-range, just an icon + name). Below FADE_END only the hologram shows; above
    // FADE_START only the marker shows; in between both render with linearly-interpolated alpha.
    private static final float HOLOGRAM_FADE_START = 14.0f;
    private static final float HOLOGRAM_FADE_END = 16.0f;

    // Marker text scaling — apparent screen size stays constant regardless of how far the
    // player is from the grave. World size grows linearly with distance (scale ∝ dist /
    // MARKER_NEAR_DIST), so a marker 100 000 blocks away renders the same on-screen pixels
    // as one 64 blocks away. On top of that we apply an "apparent factor" that's 2× at close
    // range (≤ 16 blocks) ramping down to 1× at MARKER_FAR_DIST (64+) — the marker reads
    // bigger as the player approaches, easing into the close-range hologram crossfade.
    private static final float MARKER_TEXT_SCALE = 0.025f;
    private static final float MARKER_NEAR_DIST = 16.0f;
    private static final float MARKER_FAR_DIST = 64.0f;
    private static final float MARKER_NEAR_SCALE = 2.0f;  // apparent size at ≤16 blocks (unchanged)
    private static final float MARKER_FAR_SCALE = 1.4f;   // apparent size at ≥64 blocks — bumped
                                                          // from 1.0× to make the marker more
                                                          // readable at long range without growing
                                                          // the close-range maximum.
    private static final float MARKER_Y_OFFSET = 2.5f;  // higher than hologram so it pokes above terrain

    private GraveHologramRenderer() {}

    private static boolean isConflictingMarkerModLoaded() {
        Boolean cached = cachedConflictingModLoaded;
        if (cached != null) return cached;
        boolean any = false;
        for (String id : CONFLICTING_MARKER_MOD_IDS) {
            if (ModList.get().isLoaded(id)) { any = true; break; }
        }
        cachedConflictingModLoaded = any;
        return any;
    }

    private static boolean shouldRenderMarkers() {
        PGConfig.MarkerMode mode = PGConfig.CLIENT.markerMode.get();
        return switch (mode) {
            case OFF -> false;
            case ALWAYS -> true;
            case AUTO -> !isConflictingMarkerModLoaded();
        };
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // AFTER_LEVEL is the final in-level stage — past translucent blocks (water), tripwire,
        // particles, weather, world border, and clouds regardless of where clouds render in
        // the vanilla sequence. By this point LevelRenderer has popped its camera-rotation
        // push, so the event's pose stack is effectively identity. We ignore it entirely and
        // build a fresh local PoseStack with the camera transforms we need — that way the
        // stage choice has no effect on correctness and the hologram always sits on top.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // Bring the loose-drop cache up to date from currently-loaded marker entities. Done
        // here (per-frame) instead of on EntityJoinLevelEvent because synched data arrives in
        // a packet that follows the entity-add packet — the join event is too early.
        capturePendingMarkers(mc);

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        // Single collection pass for both holograms and markers. Two reasons to merge:
        //   (1) The previous structure had `if (visible.isEmpty()) return` which exited the
        //       entire event handler when no grave was in hologram range — so markers stopped
        //       rendering as soon as the player walked past the closest grave's 16-block band.
        //   (2) Rendering all holograms then all markers means a far-grave marker can paint
        //       over a near-grave hologram. Merging into one distance-sorted pass guarantees
        //       closer graves' visuals (whichever type) always sit on top of farther ones.
        boolean markersEnabled = shouldRenderMarkers();
        int maxDist = PGConfig.CLIENT.markerMaxDistance.get();
        double markerMaxDistSq = maxDist == 0 ? Double.POSITIVE_INFINITY : (double) maxDist * maxDist;
        double fadeStartSq = HOLOGRAM_FADE_START * HOLOGRAM_FADE_START;
        boolean markerOwnOnly = PGConfig.CLIENT.markerOnlyOwnGraves.get();
        UUID viewerUuid = mc.player != null ? mc.player.getUUID() : null;

        // Prune cache entries that are "in the future" relative to the current game time (this
        // is what catches backup-loaded worlds — see ClientGraveCache for the reasoning).
        long currentTick = mc.level.getGameTime();
        ClientGraveCache.pruneFutureEntries(currentTick);

        List<GraveEntry> entries = new ArrayList<>();
        // Active graves (BEs whose chunks are loaded). Always wins over cache for the same pos
        // since the BE has the full state needed for hologram rendering.
        java.util.Set<BlockPos> seenPositions = new java.util.HashSet<>();
        for (GraveBlockEntity be : ClientGraveTracker.all()) {
            if (be.isRemoved()) continue;
            if (be.getLevel() != mc.level) continue;
            GameProfile owner = be.getOwner();
            if (owner == null) continue;

            BlockPos pos = be.getBlockPos();
            seenPositions.add(pos);
            double dx = pos.getX() + 0.5 - camPos.x;
            double dy = pos.getY() + 0.5 - camPos.y;
            double dz = pos.getZ() + 0.5 - camPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            boolean inHologram = GraveBlockEntityRenderer.isWithinHologramRange(camera, pos);
            boolean inMarker = markersEnabled
                && distSq >= fadeStartSq && distSq <= markerMaxDistSq
                && owner.getName() != null
                && (!markerOwnOnly || (viewerUuid != null && viewerUuid.equals(owner.getId())));

            if (!inHologram && !inMarker) continue;
            entries.add(new GraveEntry(be, owner, pos, be.getDeathGameTime(), distSq, inHologram, inMarker));
        }

        // Cached graves (chunk not currently loaded — but still want a marker). Cached entries
        // can only render the marker, not the hologram. Skip if the same pos already came from
        // the active tracker, if the dimension doesn't match, or if it fails the marker filters.
        //
        // Eviction is NOT done here — it's handled by:
        //   - GraveBlock.onRemove (client): fires the moment a grave is broken / picked up,
        //     even in a loaded chunk. Reliable because it's tied to the actual block-change
        //     event, not a per-frame guess.
        //   - ClientGraveCache.onChunkLoadCheck: for graves that were destroyed while their
        //     chunk was unloaded (we never saw the block change). Fires when the chunk reloads.
        // Per-frame block-state inspection used to live here but was racing against chunk
        // load/unload transitions — at the chunk boundary the block briefly appeared as air,
        // and the validation would evict the cache entry as the player walked away.
        if (markersEnabled) {
            for (ClientGraveCache.Entry cached : ClientGraveCache.all()) {
                if (!cached.dim().equals(mc.level.dimension())) continue;
                if (seenPositions.contains(cached.pos())) continue;
                GameProfile owner = cached.owner();
                if (owner.getName() == null) continue;
                if (markerOwnOnly && (viewerUuid == null || !viewerUuid.equals(owner.getId()))) continue;

                BlockPos pos = cached.pos();
                double dx = pos.getX() + 0.5 - camPos.x;
                double dy = pos.getY() + 0.5 - camPos.y;
                double dz = pos.getZ() + 0.5 - camPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;

                if (distSq < fadeStartSq) continue;     // hologram range — only active BE can render it
                if (distSq > markerMaxDistSq) continue; // beyond marker reach

                entries.add(new GraveEntry(null, owner, pos, cached.deathGameTime(), distSq, false, true));
            }
        }

        if (!entries.isEmpty()) {
            // Sort far→near so closer graves' visuals paint on top.
            entries.sort(Comparator.<GraveEntry>comparingDouble(e -> e.distSq).reversed());

            for (GraveEntry e : entries) {
                if (e.inHologram) {
                    renderHologramFor(e, camera, camPos, buf, mc);
                }
                if (e.inMarker) {
                    renderMarkerFor(e, camera, camPos, buf, mc.font);
                }
            }
        }

        // Loose-drop markers — these have no BE and no hologram, only the long-distance pin.
        // Per-viewer proximity dismissal happens here: close to the marker → drop it from the
        // local cache forever. Server-side TTL eventually purges the entity for everyone else.
        if (markersEnabled) {
            double dismissRadius = PGConfig.CLIENT.looseDropDismissRadius.get();
            double dismissRadiusSq = dismissRadius * dismissRadius;
            List<LooseMarkerEntry> looseEntries = new ArrayList<>();
            List<UUID> dismissNow = null;
            for (ClientLooseDropCache.Entry cached : ClientLooseDropCache.all()) {
                if (!cached.dim().equals(mc.level.dimension())) continue;
                GameProfile owner = cached.owner();
                if (owner.getName() == null) continue;
                if (markerOwnOnly && (viewerUuid == null || !viewerUuid.equals(owner.getId()))) continue;

                BlockPos pos = cached.pos();
                double dxL = pos.getX() + 0.5 - camPos.x;
                double dyL = pos.getY() + 0.5 - camPos.y;
                double dzL = pos.getZ() + 0.5 - camPos.z;
                double distSqL = dxL * dxL + dyL * dyL + dzL * dzL;

                if (distSqL > markerMaxDistSq) continue;

                boolean inProximity = distSqL <= dismissRadiusSq;

                if (!inProximity) {
                    // Outside dismiss radius — render normally; no capture-pos tracking needed.
                    LOOSE_DROP_CAPTURE_CAM_POS.remove(cached.id());
                    looseEntries.add(new LooseMarkerEntry(cached.id(), owner, pos, cached.deathGameTime(), distSqL, cached.kind()));
                    continue;
                }

                // Close. Decide whether the camera has actually moved since we first saw this
                // marker close. If not, the player is still on the death screen (camera locked
                // at death pos) — don't dismiss yet. If yes, the player has either respawned
                // or walked into proximity, and the dismiss is meant.
                Vec3 captureCam = LOOSE_DROP_CAPTURE_CAM_POS.computeIfAbsent(cached.id(), id -> camPos);
                double dxC = camPos.x - captureCam.x;
                double dyC = camPos.y - captureCam.y;
                double dzC = camPos.z - captureCam.z;
                double cameraDeltaSq = dxC * dxC + dyC * dyC + dzC * dzC;

                if (cameraDeltaSq >= LOOSE_DROP_CAMERA_MOVED_SQ) {
                    // Camera moved → respawn or walking → proximity-dismiss is intended.
                    if (dismissNow == null) dismissNow = new ArrayList<>();
                    dismissNow.add(cached.id());
                    LOOSE_DROP_CAPTURE_CAM_POS.remove(cached.id());
                    continue;
                }

                // Camera locked (death screen / standing still after world-load near marker).
                // Render the marker close but skip the dismiss until the camera moves —
                // typically the very next frame after the player clicks Respawn.
                looseEntries.add(new LooseMarkerEntry(cached.id(), owner, pos, cached.deathGameTime(), distSqL, cached.kind()));
            }
            if (dismissNow != null) {
                for (UUID id : dismissNow) ClientLooseDropCache.dismiss(id);
            }

            if (!looseEntries.isEmpty()) {
                looseEntries.sort(Comparator.<LooseMarkerEntry>comparingDouble(e -> e.distSq).reversed());
                for (LooseMarkerEntry e : looseEntries) {
                    renderLooseDropMarker(e, camera, camPos, buf, mc.font);
                }
            }
        }

        // Always reset state, even when nothing rendered — the per-grave loops set
        // ShaderColor / depth state and we can't leave that leaking into the HUD pass.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.enableDepthTest();
    }

    // Per-marker camera position at first close-encounter. The proximity-dismiss check refuses
    // to fire while the camera hasn't meaningfully moved since this position — that filters
    // out the death-screen window, where the camera sits locked at the death position even
    // though the marker has just spawned right there.
    //
    // Once the camera has moved (respawn teleport, or the player walking after respawn) the
    // entry is consumed and the dismiss either fires immediately (if still close) or never
    // (if the player ended up far from the marker). Robust against the SetHealth /
    // EntityData / CombatKill packet-order race that breaks isAlive() / getHealth() based
    // detection.
    private static final java.util.Map<UUID, Vec3> LOOSE_DROP_CAPTURE_CAM_POS =
        new java.util.concurrent.ConcurrentHashMap<>();

    // 1 block² — head bob / sneak crouch is sub-block; respawn teleport is always at least
    // 1+ blocks because vanilla never respawns a player exactly at their death tile.
    private static final double LOOSE_DROP_CAMERA_MOVED_SQ = 1.0;

    // Per-frame scan that captures LooseDropMarkerEntity instances into ClientLooseDropCache.
    //
    // We can't use EntityJoinLevelEvent for this: that event fires on
    // `ClientboundAddEntityPacket`, but our SynchedEntityData fields (ownerId, ownerName, etc.)
    // arrive in a separate `ClientboundSetEntityDataPacket` that follows. So at join-event time
    // the entity exists but `getOwnerId()` is still the default empty Optional. Polling once
    // per render frame is bulletproof — by the time the renderer runs, sync packets have long
    // since been applied. Cost is O(loaded entities) per frame, dominated by an `instanceof`
    // check; trivial vs. the full render pass.
    private static void capturePendingMarkers(Minecraft mc) {
        if (mc.level == null) return;
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LooseDropMarkerEntity m && m.getOwnerId().isPresent()) {
                ClientLooseDropCache.captureFromEntity(m);
            }
        }
    }

    private static void renderHologramFor(GraveEntry e, Camera camera, Vec3 camPos,
                                          MultiBufferSource.BufferSource buf, Minecraft mc) {
        BlockPos pos = e.pos;

        // Occlusion-based fade: dim the hologram proportionally to opaque blocks between camera
        // and the hologram center. Floor at 0.65 because the TRANSLUCENT blend at low alpha lets
        // the framebuffer (i.e. the obstructing block) dominate visually.
        Vec3 holoCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5);
        int occluding = countOccludingBlocks(camPos, holoCenter, mc.level);
        float fade = Math.max(0.65f, 1.0f / (1.0f + occluding * 0.25f));

        // Distance crossfade-out across HOLOGRAM_FADE_START..HOLOGRAM_FADE_END. The marker pass
        // for the same grave picks up the inverse ramp so the hand-off is invisible.
        double holoDist = Math.sqrt(e.distSq);
        float distFadeOut = (float) Math.max(0.0, Math.min(1.0,
            (HOLOGRAM_FADE_END - holoDist) / (HOLOGRAM_FADE_END - HOLOGRAM_FADE_START)));
        fade *= distFadeOut;
        if (fade <= 0.001f) return;  // fully faded out — skip the draw entirely

        PoseStack localPose = new PoseStack();
        localPose.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        localPose.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0f));
        localPose.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, fade);
        RenderSystem.disableDepthTest();

        GraveBlockEntityRenderer.renderHologram(e.be, localPose, buf, camera);
        // Per-grave flush so close-range hologram bg doesn't get batched into the next grave's
        // text and end up painting over it.
        buf.endBatch();
    }

    private static void renderMarkerFor(GraveEntry e, Camera camera, Vec3 camPos,
                                        MultiBufferSource.BufferSource buf, Font font) {
        // Crossfade-in alpha: 0 at HOLOGRAM_FADE_START, 1 at HOLOGRAM_FADE_END. Mirror of the
        // hologram's fade-out so both layers are at full visibility on the outside of the band.
        double dist = Math.sqrt(e.distSq);
        float fadeIn = (float) Math.max(0.0, Math.min(1.0,
            (dist - HOLOGRAM_FADE_START) / (HOLOGRAM_FADE_END - HOLOGRAM_FADE_START)));
        if (fadeIn <= 0.001f) return;

        renderOneMarker(new MarkerEntry(e.be, e.owner, e.pos, e.deathGameTime, fadeIn),
            camera, camPos, buf, font);
    }

    private static void renderOneMarker(MarkerEntry e, Camera camera, Vec3 camPos,
                                        MultiBufferSource.BufferSource buf, Font font) {
        BlockPos pos = e.pos;

        // Constant apparent screen size. World size = base × (dist / NEAR_DIST), so apparent
        // = world / dist stays equal to (base / NEAR_DIST) at every distance. That holds even
        // at 100 000 blocks, the only limit is float precision. On top of that, multiply by
        // an "apparent factor" that's 2× near (≤16) and ramps to 1× by MARKER_FAR_DIST (64),
        // making the marker visibly bigger as the player approaches.
        double dx = pos.getX() + 0.5 - camPos.x;
        double dy = pos.getY() + MARKER_Y_OFFSET - camPos.y;
        double dz = pos.getZ() + 0.5 - camPos.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float t = (float) Math.max(0.0, Math.min(1.0,
            (dist - MARKER_NEAR_DIST) / (MARKER_FAR_DIST - MARKER_NEAR_DIST)));
        float apparentFactor = MARKER_NEAR_SCALE + t * (MARKER_FAR_SCALE - MARKER_NEAR_SCALE);
        float distanceScale = (float) (dist / MARKER_NEAR_DIST);
        float scale = MARKER_TEXT_SCALE * apparentFactor * distanceScale;

        PoseStack pose = new PoseStack();
        pose.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        pose.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0f));
        pose.translate(
            pos.getX() + 0.5 - camPos.x,
            pos.getY() + MARKER_Y_OFFSET - camPos.y,
            pos.getZ() + 0.5 - camPos.z
        );

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, e.fadeIn);
        RenderSystem.disableDepthTest();

        pose.pushPose();
        pose.mulPose(camera.rotation());
        pose.scale(-scale, -scale, scale);

        // Single-line marker text matching the close-range hologram's color palette so the
        // visual style stays consistent across the crossfade band:
        //   skull         — white  (matches the value color in the hologram)
        //   <name>        — yellow (same as `<name> was killed` line in the hologram)
        //   "died"/"ago"  — gray   (matches the "Items:"/"XP:" label color, applied as outer
        //                           style on the translatable so the connective words inherit it)
        //   <time>        — white  (matches numeric values; applied to the time argument)
        //   <distance>    — white  (same value-color treatment, formatted in blocks/m)
        // The skull glyph (☠ U+2620) is kept as a literal because it's a universal symbol
        // and renders via vanilla's unicode_page_26.png fallback in any locale.
        long ageSeconds = computeAgeSeconds(e.deathGameTime);
        int distBlocks = (int) Math.round(dist);
        Component markerText = Component.empty()
            .append(Component.literal("☠ ").withStyle(ChatFormatting.WHITE))
            .append(Component.translatable(
                "perfectgraves.marker.died_ago",
                Component.literal(e.owner.getName()).withStyle(ChatFormatting.YELLOW),
                Component.literal(formatAge(ageSeconds)).withStyle(ChatFormatting.WHITE),
                Component.literal(distBlocks + "m").withStyle(ChatFormatting.WHITE)
            ).withStyle(ChatFormatting.GRAY));

        List<Component> lines = List.of(markerText);

        int maxWidth = 0;
        for (Component line : lines) {
            int w = font.width(line);
            if (w > maxWidth) maxWidth = w;
        }
        int totalHeight = lines.size() * font.lineHeight;
        float halfW = maxWidth / 2f;
        float halfH = totalHeight / 2f;

        Matrix4f m = pose.last().pose();
        int fullBright = LightTexture.pack(15, 15);
        drawMarkerBackground(m, buf, fullBright,
            -halfW - 3, -halfH - 2, halfW + 3, halfH + 2,
            0xE0000000, net.minecraft.client.renderer.RenderType.textBackgroundSeeThrough());

        float y = -halfH;
        for (Component line : lines) {
            float x = -font.width(line) / 2f;
            font.drawInBatch(line, x, y, 0xFFFFFFFF, false, m, buf,
                Font.DisplayMode.SEE_THROUGH, 0, fullBright);
            y += font.lineHeight;
        }

        pose.popPose();
        buf.endBatch();
    }

    private static long computeAgeSeconds(long deathGameTime) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return 0;
        long ageTicks = Math.max(0L, level.getGameTime() - deathGameTime);
        return ageTicks / 20L;
    }

    private static String formatAge(long seconds) {
        return com.risen.perfectgraves.util.AgeFormat.formatAge(seconds);
    }

    // Background quad mirroring GraveBlockEntityRenderer.drawBackgroundRect. Kept private since
    // the rest of the file doesn't need it and exposing it would couple the API more than needed.
    private static void drawMarkerBackground(Matrix4f m, MultiBufferSource buf, int light,
                                             float x1, float y1, float x2, float y2, int argb,
                                             net.minecraft.client.renderer.RenderType bgType) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>>  8) & 0xFF) / 255f;
        float b = ( argb         & 0xFF) / 255f;
        var vc = buf.getBuffer(bgType);
        vc.vertex(m, x1, y1, 0f).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x1, y2, 0f).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x2, y2, 0f).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x2, y1, 0f).color(r, g, b, a).uv2(light).endVertex();
    }


    private record MarkerEntry(@Nullable GraveBlockEntity be, GameProfile owner, BlockPos pos,
                               long deathGameTime, float fadeIn) {}

    private record LooseMarkerEntry(UUID id, GameProfile owner, BlockPos pos, long deathGameTime,
                                    double distSq, LooseDropMarkerEntity.DropKind kind) {}

    // Loose-drop marker rendering. Constant apparent screen size like the grave marker, but no
    // hologram crossfade band — there's nothing close-range to fade *to*; the marker just stays
    // at full alpha down to the proximity dismiss radius, then disappears. Distinct visual:
    // gold/yellow palette and an "items dropped" translation key so players can tell the marker
    // labels loose items, not a grave.
    private static void renderLooseDropMarker(LooseMarkerEntry e, Camera camera, Vec3 camPos,
                                              MultiBufferSource.BufferSource buf, Font font) {
        BlockPos pos = e.pos;
        double dx = pos.getX() + 0.5 - camPos.x;
        double dy = pos.getY() + MARKER_Y_OFFSET - camPos.y;
        double dz = pos.getZ() + 0.5 - camPos.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float t = (float) Math.max(0.0, Math.min(1.0,
            (dist - MARKER_NEAR_DIST) / (MARKER_FAR_DIST - MARKER_NEAR_DIST)));
        float apparentFactor = MARKER_NEAR_SCALE + t * (MARKER_FAR_SCALE - MARKER_NEAR_SCALE);
        float distanceScale = (float) (dist / MARKER_NEAR_DIST);
        float scale = MARKER_TEXT_SCALE * apparentFactor * distanceScale;

        PoseStack pose = new PoseStack();
        pose.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        pose.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0f));
        pose.translate(
            pos.getX() + 0.5 - camPos.x,
            pos.getY() + MARKER_Y_OFFSET - camPos.y,
            pos.getZ() + 0.5 - camPos.z
        );

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableDepthTest();

        pose.pushPose();
        pose.mulPose(camera.rotation());
        pose.scale(-scale, -scale, scale);

        long ageSeconds = computeAgeSeconds(e.deathGameTime);
        int distBlocks = (int) Math.round(dist);
        Component markerText = Component.empty()
            .append(Component.literal("☠ ").withStyle(ChatFormatting.GOLD))
            .append(Component.translatable(
                "perfectgraves.marker.loose_items_died_ago",
                Component.literal(e.owner.getName()).withStyle(ChatFormatting.GOLD),
                Component.literal(formatAge(ageSeconds)).withStyle(ChatFormatting.WHITE),
                Component.literal(distBlocks + "m").withStyle(ChatFormatting.WHITE)
            ).withStyle(ChatFormatting.GRAY));

        List<Component> lines = List.of(markerText);

        int maxWidth = 0;
        for (Component line : lines) {
            int w = font.width(line);
            if (w > maxWidth) maxWidth = w;
        }
        int totalHeight = lines.size() * font.lineHeight;
        float halfW = maxWidth / 2f;
        float halfH = totalHeight / 2f;

        Matrix4f m = pose.last().pose();
        int fullBright = LightTexture.pack(15, 15);
        drawMarkerBackground(m, buf, fullBright,
            -halfW - 3, -halfH - 2, halfW + 3, halfH + 2,
            0xE0000000, net.minecraft.client.renderer.RenderType.textBackgroundSeeThrough());

        float y = -halfH;
        for (Component line : lines) {
            float x = -font.width(line) / 2f;
            font.drawInBatch(line, x, y, 0xFFFFFFFF, false, m, buf,
                Font.DisplayMode.SEE_THROUGH, 0, fullBright);
            y += font.lineHeight;
        }

        pose.popPose();
        buf.endBatch();
    }

    // Common per-grave info used by both the hologram and marker render paths. `be` is null for
    // cached-only entries (the grave's chunk isn't currently loaded) — those can only render the
    // marker, not the hologram, since the hologram needs full BE state (items / xp / timers).
    // inHologram / inMarker capture which visuals this grave qualifies for so the unified loop
    // can call either or both render-fors without re-running the band/visibility checks.
    private record GraveEntry(
        @Nullable GraveBlockEntity be,
        GameProfile owner,
        BlockPos pos,
        long deathGameTime,
        double distSq,
        boolean inHologram,
        boolean inMarker
    ) {}

    // Steps along the camera→hologram ray at half-block intervals and counts unique block
    // positions whose state reports as a vision-blocking block (canOcclude). Glass, leaves,
    // air, fluids → don't count (you can see through them anyway). Stone, dirt, wood → count.
    // Half-block steps mean we won't miss thin walls; deduping on lastPos avoids double-counting
    // a single block crossed by multiple steps.
    private static int countOccludingBlocks(Vec3 from, Vec3 to, ClientLevel level) {
        double dist = from.distanceTo(to);
        if (dist < 0.5) return 0;
        int steps = Math.max(1, (int) (dist / 0.5));
        int count = 0;
        BlockPos lastPos = null;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / (double) steps;
            Vec3 p = from.lerp(to, t);
            BlockPos bp = BlockPos.containing(p);
            if (bp.equals(lastPos)) continue;
            lastPos = bp;
            if (!level.isLoaded(bp)) continue;
            BlockState state = level.getBlockState(bp);
            if (state.canOcclude()) count++;
        }
        return count;
    }

    // ClientLevel disposal. Two cases:
    //   (a) Real world exit (disconnect / leave to main menu) — mc.level is null or equals the
    //       level being unloaded. Clear tracker and cache state.
    //   (b) Dimension change — mc.level already points to the NEW ClientLevel because Forge
    //       fires LevelEvent.Load for the new one BEFORE LevelEvent.Unload for the old one.
    //       Touching tracker or cache state here would wipe data populated by the Load handler
    //       that just ran. Just save the cache to disk and leave in-memory state alone.
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean leavingWorld = mc.level == null || mc.level == event.getLevel();
        if (leavingWorld) {
            ClientGraveTracker.clear();
        }
        ClientGraveCache.onWorldUnload(leavingWorld);
        ClientLooseDropCache.onWorldUnload(leavingWorld);
    }

    // ClientLevel arrival (initial connect, dimension change, post-respawn). Load the persistent
    // marker cache for this world so markers from prior sessions appear immediately, before any
    // chunks have streamed in.
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            ClientGraveCache.onWorldLoad(Minecraft.getInstance());
            ClientLooseDropCache.onWorldLoad(Minecraft.getInstance());
        }
    }

    // Lazy stale-eviction. When a chunk arrives client-side, walk the cached entries inside it
    // and drop any whose pos no longer holds our grave block — that grave was destroyed at some
    // point we couldn't observe (broken / self-destruct fired with chunk unloaded).
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        var pos = event.getChunk().getPos();
        ClientGraveCache.onChunkLoadCheck(level, pos.x, pos.z);
    }
}
