package com.risen.perfectgraves;

import com.mojang.logging.LogUtils;
import com.risen.perfectgraves.accessories.ExtendedInventoryRegistry;
import com.risen.perfectgraves.accessories.integrations.CuriosProvider;
import com.risen.perfectgraves.claims.ClaimRegistry;
import com.risen.perfectgraves.claims.integrations.FTBChunksProvider;
import com.risen.perfectgraves.claims.integrations.FlanProvider;
import com.risen.perfectgraves.claims.integrations.OPACProvider;
import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.registry.PGBlockEntities;
import com.risen.perfectgraves.registry.PGBlocks;
import com.risen.perfectgraves.registry.PGEntities;
import com.risen.perfectgraves.registry.PGMenus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(PerfectGraves.MOD_ID)
public final class PerfectGraves {

    public static final String MOD_ID = "perfectgraves";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PerfectGraves(FMLJavaModLoadingContext context) {
        var modBus = context.getModEventBus();
        modBus.addListener(this::onCommonSetup);

        PGBlocks.BLOCKS.register(modBus);
        PGBlockEntities.BLOCK_ENTITIES.register(modBus);
        PGMenus.MENUS.register(modBus);
        PGEntities.ENTITIES.register(modBus);

        context.registerConfig(ModConfig.Type.COMMON, PGConfig.SPEC, MOD_ID + "-common.toml");
        context.registerConfig(ModConfig.Type.CLIENT, PGConfig.CLIENT_SPEC, MOD_ID + "-client.toml");

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            var enabledClaims = new java.util.HashSet<String>(PGConfig.COMMON.enabledIntegrations.get());
            if (ModList.get().isLoaded("ftbchunks") && enabledClaims.contains("ftbchunks")) {
                ClaimRegistry.register(new FTBChunksProvider());
            }
            if (ModList.get().isLoaded("openpartiesandclaims") && enabledClaims.contains("openpartiesandclaims")) {
                ClaimRegistry.register(new OPACProvider());
            }
            if (ModList.get().isLoaded("flan") && enabledClaims.contains("flan")) {
                ClaimRegistry.register(new FlanProvider());
            }
            // Accessory providers aren't covered by the claim integrations list; they always
            // register when present since there's no "disable" use case for Curios on Forge 1.20.1.
            if (ModList.get().isLoaded("curios")) {
                ExtendedInventoryRegistry.register(new CuriosProvider());
            }
        });
        LOGGER.info("[{}] common setup complete", MOD_ID);
    }
}
