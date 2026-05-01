package com.risen.perfectgraves.claims;

import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.util.PGLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ClaimRegistry {

    private static final List<ClaimProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ClaimRegistry() {}

    public static void register(ClaimProvider p) {
        PROVIDERS.add(p);
        PGLog.info(PGLog.CLAIMS, "registered provider: {}", p.getModId());
    }

    public static List<ClaimProvider> providers() {
        return List.copyOf(PROVIDERS);
    }

    // Per-search context. Caches the AND-reduce result per chunk to avoid re-querying the
    // same chunk repeatedly during a shell search. Intended lifetime: one PlacementCascade
    // invocation. Not thread-safe — one context per cascade, main server thread only.
    public static final class SearchContext {

        private final Map<ChunkPos, Boolean> cache = new HashMap<>();

        public boolean canPlace(Level level, BlockPos pos, @Nullable Player player) {
            if (!PGConfig.COMMON.respectClaims.get()) return true;

            ChunkPos cp = new ChunkPos(pos);
            Boolean cached = cache.get(cp);
            if (cached != null) return cached;

            for (ClaimProvider p : PROVIDERS) {
                if (!p.isLoaded()) continue;
                boolean allowed;
                try {
                    allowed = p.canPlaceGrave(level, pos, player);
                } catch (Throwable t) {
                    // Permissive on error per resolved design decision: a broken provider
                    // should not block all grave placement. Cascade can still fall through
                    // to virtual grave if the grave actually lands in a bad spot.
                    PGLog.warn(PGLog.CLAIMS, "provider {} threw in canPlaceGrave at {}; treating as permissive",
                        p.getModId(), pos, t);
                    continue;
                }
                if (!allowed) {
                    PGLog.debug(PGLog.CLAIMS, "provider {} denied placement at {}", p.getModId(), pos);
                    cache.put(cp, false);
                    return false;
                }
            }
            cache.put(cp, true);
            return true;
        }
    }
}
