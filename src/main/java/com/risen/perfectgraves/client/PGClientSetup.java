package com.risen.perfectgraves.client;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.registry.PGBlockEntities;
import com.risen.perfectgraves.registry.PGEntities;
import com.risen.perfectgraves.registry.PGMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PGClientSetup {

    private PGClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(PGMenus.GRAVE.get(), GraveScreen::new);
            MenuScreens.register(PGMenus.VIRTUAL_GRAVE.get(), VirtualGraveScreen::new);
            MenuScreens.register(PGMenus.HISTORY_LIST.get(), DeathHistoryListScreen::new);
            MenuScreens.register(PGMenus.HISTORY_DETAIL.get(), DeathHistoryDetailScreen::new);
        });
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(PGBlockEntities.GRAVE.get(), GraveBlockEntityRenderer::new);
        event.registerEntityRenderer(PGEntities.SAFE_XP.get(), SafeXPEntityRenderer::new);
        event.registerEntityRenderer(PGEntities.LOOSE_DROP_MARKER.get(), LooseDropMarkerRenderer::new);
    }
}
