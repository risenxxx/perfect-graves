package com.risen.perfectgraves.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record SafePosData(ResourceKey<Level> dimension, BlockPos pos, long gameTime) {}
