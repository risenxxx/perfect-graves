package com.risen.perfectgraves.virtual;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.config.PGConfig;
import com.risen.perfectgraves.grave.QuickPickup;
import com.risen.perfectgraves.history.DeathHistoryData;
import com.risen.perfectgraves.history.DeathHistoryEntry;
import com.risen.perfectgraves.history.DeathHistoryListMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.MenuProvider;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID)
public final class VirtualGraveCommand {

    private VirtualGraveCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        int opLevel = Math.max(0, Math.min(4, PGConfig.COMMON.restoreCommandPermissionLevel.get()));
        int historyLevel = Math.max(0, Math.min(4, PGConfig.COMMON.deathHistoryCommandPermissionLevel.get()));

        event.getDispatcher().register(
            Commands.literal("grave")
                .then(Commands.literal("list")
                    .requires(src -> src.hasPermission(opLevel))
                    .executes(ctx -> openList(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                    .then(Commands.argument("player", EntityArgument.player())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> openList(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("restore")
                    .requires(src -> src.hasPermission(opLevel))
                    .then(Commands.argument("id", UuidArgument.uuid())
                        .executes(ctx -> restore(ctx.getSource(), UuidArgument.getUuid(ctx, "id")))))
                .then(Commands.literal("history")
                    .requires(src -> src.hasPermission(historyLevel))
                    .executes(ctx -> openHistory(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                    .then(Commands.argument("player", EntityArgument.player())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> openHistory(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("debug")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> debug(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
        );
    }

    private static int openHistory(CommandSourceStack src, ServerPlayer target) throws CommandSyntaxException {
        if (!PGConfig.COMMON.deathHistoryEnabled.get()) {
            src.sendSuccess(() -> Component.translatable("perfectgraves.history.disabled")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ServerPlayer opener = src.getPlayerOrException();
        java.util.List<DeathHistoryEntry> entries = DeathHistoryData.get(src.getServer()).getFor(target.getUUID());
        boolean isSelf = opener.getUUID().equals(target.getUUID());

        if (entries.isEmpty()) {
            Component msg = isSelf
                ? Component.translatable("perfectgraves.history.no_deaths_self")
                : Component.translatable("perfectgraves.history.no_deaths_other", target.getGameProfile().getName());
            src.sendSuccess(() -> msg.copy().withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        opener.openMenu(DeathHistoryListMenu.listProviderFor(
            target.getUUID(), target.getGameProfile().getName(), src.getServer()));
        return entries.size();
    }

    private static int openList(CommandSourceStack src, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer opener = src.getPlayerOrException();
        List<VirtualGrave> graves = VirtualGraveData.get(src.getServer()).getFor(target.getUUID());
        boolean isSelf = opener.getUUID().equals(target.getUUID());

        if (graves.isEmpty()) {
            Component msg = isSelf
                ? Component.translatable("perfectgraves.virtual.no_graves_self")
                : Component.translatable("perfectgraves.virtual.no_graves_other",
                    target.getGameProfile().getName());
            src.sendSuccess(() -> msg.copy().withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        Component title = isSelf
            ? Component.translatable("perfectgraves.virtual.title.self", graves.size())
            : Component.translatable("perfectgraves.virtual.title.other",
                target.getGameProfile().getName(), graves.size());
        boolean selfView = isSelf;
        opener.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return title; }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                return new VirtualGraveMenu(id, inv, graves, selfView);
            }
        });
        return graves.size();
    }

    private static int restore(CommandSourceStack src, UUID graveId) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        VirtualGraveData data = VirtualGraveData.get(src.getServer());
        Optional<VirtualGrave> vg = data.find(graveId);

        if (vg.isEmpty()) {
            src.sendFailure(Component.translatable("perfectgraves.virtual.restore.not_found", graveId.toString()));
            return 0;
        }
        if (!vg.get().ownerId().equals(player.getUUID()) && !src.hasPermission(2)) {
            src.sendFailure(Component.translatable("perfectgraves.virtual.restore.not_owner"));
            return 0;
        }

        data.remove(graveId);
        QuickPickup.restoreVirtual(player, vg.get());
        src.sendSuccess(() -> Component.translatable("perfectgraves.virtual.restore.success",
            vg.get().items().size(), vg.get().xp()).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int debug(CommandSourceStack src, ServerPlayer target) {
        List<VirtualGrave> graves = VirtualGraveData.get(src.getServer()).getFor(target.getUUID());
        boolean isSelf = src.getPlayer() != null && src.getPlayer().getUUID().equals(target.getUUID());
        Component header = isSelf
            ? Component.translatable("perfectgraves.virtual.debug.header.self", graves.size())
            : Component.translatable("perfectgraves.virtual.debug.header.other",
                target.getGameProfile().getName(), graves.size());
        src.sendSuccess(() -> header.copy().withStyle(ChatFormatting.AQUA), false);
        for (VirtualGrave vg : graves) {
            // Wrap UUID in a clickable component: SUGGEST_COMMAND pre-fills /grave restore <uuid>
            // in chat (so the OP can hit Enter to commit, or copy the text from the input).
            // Hover doubles as a "click to copy/run" hint in any locale.
            String restoreCmd = "/grave restore " + vg.id();
            Component uuidComp = Component.literal(vg.id().toString()).withStyle(s -> s
                .withColor(ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, restoreCmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable("perfectgraves.virtual.debug.uuid_hover"))));

            src.sendSuccess(() -> Component.translatable("perfectgraves.virtual.debug.entry",
                uuidComp,
                vg.items().size(),
                vg.xp(),
                vg.deathPos().toShortString(),
                vg.dimension().location().toString()
            ).withStyle(ChatFormatting.GRAY), false);
        }
        return graves.size();
    }
}
