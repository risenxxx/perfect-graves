package com.risen.perfectgraves.registry;

import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.grave.GraveContainerMenu;
import com.risen.perfectgraves.history.DeathHistoryDetailMenu;
import com.risen.perfectgraves.history.DeathHistoryListMenu;
import com.risen.perfectgraves.virtual.VirtualGraveMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class PGMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, PerfectGraves.MOD_ID);

    public static final RegistryObject<MenuType<GraveContainerMenu>> GRAVE = MENUS.register("grave",
        () -> IForgeMenuType.create(GraveContainerMenu::fromNetwork));

    public static final RegistryObject<MenuType<VirtualGraveMenu>> VIRTUAL_GRAVE = MENUS.register("virtual_grave",
        () -> IForgeMenuType.create(VirtualGraveMenu::fromNetwork));

    public static final RegistryObject<MenuType<DeathHistoryListMenu>> HISTORY_LIST = MENUS.register("history_list",
        () -> IForgeMenuType.create(DeathHistoryListMenu::fromNetwork));

    public static final RegistryObject<MenuType<DeathHistoryDetailMenu>> HISTORY_DETAIL = MENUS.register("history_detail",
        () -> IForgeMenuType.create(DeathHistoryDetailMenu::fromNetwork));

    private PGMenus() {}
}
