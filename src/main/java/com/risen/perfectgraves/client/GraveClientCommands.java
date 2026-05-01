package com.risen.perfectgraves.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.risen.perfectgraves.PerfectGraves;
import com.risen.perfectgraves.config.PGConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Client-only Brigadier commands for live-toggling marker settings without restarting or
// alt-tabbing to edit the TOML. Forge's RegisterClientCommandsEvent puts these on the
// CLIENT command dispatcher, which matches before forwarding to the server — so /grave
// marker ... is handled locally while /grave list / restore / debug still hit the server
// (registered via RegisterCommandsEvent in VirtualGraveCommand).
@Mod.EventBusSubscriber(modid = PerfectGraves.MOD_ID, value = Dist.CLIENT)
public final class GraveClientCommands {

    private static final String[] MODE_SUGGESTIONS = { "auto", "always", "off" };

    private GraveClientCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("grave")
                .then(Commands.literal("marker")
                    .executes(ctx -> showAll(ctx.getSource()))
                    .then(Commands.literal("mode")
                        .executes(ctx -> showOne(ctx.getSource(), "mode", PGConfig.CLIENT.markerMode.get().name()))
                        .then(Commands.argument("value", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(MODE_SUGGESTIONS, b))
                            .executes(ctx -> setMode(ctx.getSource(), StringArgumentType.getString(ctx, "value")))))
                    .then(Commands.literal("own-only")
                        .executes(ctx -> showOne(ctx.getSource(), "own-only", String.valueOf(PGConfig.CLIENT.markerOnlyOwnGraves.get())))
                        .then(Commands.argument("value", BoolArgumentType.bool())
                            .executes(ctx -> setOwnOnly(ctx.getSource(), BoolArgumentType.getBool(ctx, "value")))))
                    .then(Commands.literal("dismiss-radius")
                        .executes(ctx -> showOne(ctx.getSource(), "dismiss-radius", String.valueOf(PGConfig.CLIENT.looseDropDismissRadius.get())))
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(1.0, 64.0))
                            .executes(ctx -> setDismissRadius(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "value")))))
                    .then(Commands.literal("max-distance")
                        .executes(ctx -> showOne(ctx.getSource(), "max-distance", String.valueOf(PGConfig.CLIENT.markerMaxDistance.get())))
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100000))
                            .executes(ctx -> setMaxDistance(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "value")))))
                )
        );
    }

    private static int showAll(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("☠ Perfect Graves marker settings")
            .withStyle(ChatFormatting.GOLD), false);
        sendKV(src, "  mode:           ", PGConfig.CLIENT.markerMode.get().name());
        sendKV(src, "  own-only:       ", String.valueOf(PGConfig.CLIENT.markerOnlyOwnGraves.get()));
        sendKV(src, "  dismiss-radius: ", String.valueOf(PGConfig.CLIENT.looseDropDismissRadius.get()));
        sendKV(src, "  max-distance:   ", String.valueOf(PGConfig.CLIENT.markerMaxDistance.get()));
        return 1;
    }

    private static int showOne(CommandSourceStack src, String key, String value) {
        src.sendSuccess(() -> Component.empty()
            .append(Component.literal("marker." + key + ": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
        return 1;
    }

    private static int setMode(CommandSourceStack src, String raw) {
        PGConfig.MarkerMode mode;
        try {
            mode = PGConfig.MarkerMode.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            src.sendFailure(Component.literal("Unknown mode '" + raw + "' (expected: auto, always, off)"));
            return 0;
        }
        PGConfig.CLIENT.markerMode.set(mode);
        PGConfig.CLIENT.markerMode.save();
        src.sendSuccess(() -> formatSet("mode", mode.name()), false);
        return 1;
    }

    private static int setOwnOnly(CommandSourceStack src, boolean value) {
        PGConfig.CLIENT.markerOnlyOwnGraves.set(value);
        PGConfig.CLIENT.markerOnlyOwnGraves.save();
        src.sendSuccess(() -> formatSet("own-only", String.valueOf(value)), false);
        return 1;
    }

    private static int setDismissRadius(CommandSourceStack src, double value) {
        PGConfig.CLIENT.looseDropDismissRadius.set(value);
        PGConfig.CLIENT.looseDropDismissRadius.save();
        src.sendSuccess(() -> formatSet("dismiss-radius", String.valueOf(value)), false);
        return 1;
    }

    private static int setMaxDistance(CommandSourceStack src, int value) {
        PGConfig.CLIENT.markerMaxDistance.set(value);
        PGConfig.CLIENT.markerMaxDistance.save();
        src.sendSuccess(() -> formatSet("max-distance", String.valueOf(value)), false);
        return 1;
    }

    private static Component formatSet(String key, String value) {
        return Component.empty()
            .append(Component.literal("marker." + key).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(value).withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
    }

    private static void sendKV(CommandSourceStack src, String key, String value) {
        src.sendSuccess(() -> Component.empty()
            .append(Component.literal(key).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
    }
}
