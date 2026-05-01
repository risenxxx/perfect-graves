package com.risen.perfectgraves.client;

import com.risen.perfectgraves.grave.GraveBlockEntity;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Client-only registry of loaded grave block entities, populated via GraveBlockEntity.onLoad()
// and drained via setRemoved(). Lets the hologram-render pass (after translucent blocks) find
// its work without walking every loaded chunk. Safe to reference from common code — this class
// imports no client-only Minecraft types, so it's loadable on a dedicated server even though
// it'll only ever actually be touched on the client side.
public final class ClientGraveTracker {

    private static final Set<GraveBlockEntity> LIVE =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ClientGraveTracker() {}

    public static void register(GraveBlockEntity be) {
        LIVE.add(be);
    }

    public static void unregister(GraveBlockEntity be) {
        LIVE.remove(be);
    }

    public static Set<GraveBlockEntity> all() {
        return LIVE;
    }

    public static void clear() {
        LIVE.clear();
    }
}
