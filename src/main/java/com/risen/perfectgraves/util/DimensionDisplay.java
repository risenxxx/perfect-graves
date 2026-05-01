package com.risen.perfectgraves.util;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// Resolves a dimension to its localized display name.
//
// Vanilla 1.20.1 surprisingly does NOT ship translation keys for the three core dimensions
// — `dimension.minecraft.overworld` / `the_nether` / `the_end` are absent from
// assets/minecraft/lang/*.json. So we ship our own keys under `perfectgraves.dimension.*`
// for the vanilla three, and fall back to the (rarely-existing) `dimension.<ns>.<path>`
// convention that some mods adopt, finally landing on a prettified path for unknown dims.
public final class DimensionDisplay {

    private DimensionDisplay() {}

    public static Component name(ResourceKey<Level> dim) {
        String ns = dim.location().getNamespace();
        String path = dim.location().getPath();

        if ("minecraft".equals(ns)
            && ("overworld".equals(path) || "the_nether".equals(path) || "the_end".equals(path))) {
            return Component.translatable("perfectgraves.dimension." + path);
        }

        return Component.translatableWithFallback("dimension." + ns + "." + path, prettify(path));
    }

    private static String prettify(String path) {
        if (path == null || path.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(path.length());
        boolean nextUpper = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/' || c == '-') {
                sb.append(' ');
                nextUpper = true;
                continue;
            }
            if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
