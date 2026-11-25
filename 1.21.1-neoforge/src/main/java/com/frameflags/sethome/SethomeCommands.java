package com.frameflags.sethome;

import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.HomeRecord;
import com.frameflags.sethome.core.HomeResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Brigadier adapter: six command forms, no permission requirement. */
public final class SethomeCommands {
    private static final SuggestionProvider<CommandSourceStack> HOME_NAMES = (context, builder) -> {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            for (String name : Sethome.data().list(player.getUUID())) builder.suggest(name);
        }
        return builder.buildFuture();
    };

    private SethomeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sethome")
                .executes(context -> set(context, "home"))
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(context -> set(context, StringArgumentType.getString(context, "name")))));

        dispatcher.register(Commands.literal("home")
                .executes(context -> go(context, "home"))
                .then(Commands.literal("list").executes(SethomeCommands::list))
                .then(Commands.argument("name", StringArgumentType.string()).suggests(HOME_NAMES)
                        .executes(context -> go(context, StringArgumentType.getString(context, "name")))));

        dispatcher.register(Commands.literal("delhome")
                .executes(context -> remove(context, "home"))
                .then(Commands.argument("name", StringArgumentType.string()).suggests(HOME_NAMES)
                        .executes(context -> remove(context, StringArgumentType.getString(context, "name")))));
    }

    private static int set(CommandContext<CommandSourceStack> context, String rawName) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            message(source, null, "Only players can use this command.", false);
            return 0;
        }
        String normalized = HomeData.normalize(rawName);
        HomeRecord record = new HomeRecord(
                player.getX() - 0.5D, player.getY() - 0.5D, player.getZ() - 0.5D,
                player.getYRot(), player.getXRot(),
                player.level().dimension().location().toString());
        HomeResult result = Sethome.data().set(player.getUUID(), rawName, record, Sethome.config().maxHomes);
        switch (result) {
            case OK:
                Sethome.saveHomes();
                message(source, player, ChatFormatting.GREEN + "Home '" + normalized + "' set at " + record.format() + ".", true);
                return 1;
            case RESERVED_NAME:
                message(source, player, ChatFormatting.RED + "'list' is reserved and cannot be used as a home name.", false);
                return 0;
            case INVALID_NAME:
                message(source, player, ChatFormatting.RED + "Home name cannot be blank.", false);
                return 0;
            case LIMIT_REACHED:
                message(source, player, ChatFormatting.RED + "Home limit reached (" + Sethome.config().maxHomes + ").", false);
                return 0;
            default:
                message(source, player, ChatFormatting.RED + "Could not set home.", false);
                return 0;
        }
    }

    private static int go(CommandContext<CommandSourceStack> context, String rawName) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            message(source, null, "Only players can use this command.", false);
            return 0;
        }
        String normalized = HomeData.normalize(rawName);
        HomeRecord record = Sethome.data().get(player.getUUID(), rawName);
        if (record == null) {
            message(source, player, ChatFormatting.RED + "Home '" + normalized + "' not found.", false);
            return 0;
        }
        int seconds = Math.max(0, Sethome.config().countdownSeconds);
        if (seconds > 0) {
            long deadline = source.getServer().getTickCount() + seconds * 20L;
            Sethome.queue().schedule(player.getUUID(), normalized, deadline);
            message(source, player, ChatFormatting.YELLOW + "Teleporting to home '" + normalized + "' in " + seconds + "s.", true);
            return 1;
        }
        Sethome.teleport(player, record, source.getServer());
        message(source, player, ChatFormatting.GREEN + "Teleported to home '" + normalized + "'.", true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            message(source, null, "Only players can use this command.", false);
            return 0;
        }
        java.util.List<String> names = Sethome.data().list(player.getUUID());
        StringBuilder text = new StringBuilder(ChatFormatting.AQUA + "Homes: ");
        if (names.isEmpty()) {
            text.append("none");
        } else {
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) text.append(ChatFormatting.GRAY + ", " + ChatFormatting.AQUA);
                String name = names.get(i);
                HomeRecord record = Sethome.data().get(player.getUUID(), name);
                text.append(name).append(ChatFormatting.GRAY + " [")
                        .append(record == null ? "?" : record.format())
                        .append(ChatFormatting.GRAY + "]");
            }
        }
        // Exactly one chat message, regardless of home count.
        message(source, player, text.toString(), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> context, String rawName) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            message(source, null, "Only players can use this command.", false);
            return 0;
        }
        String normalized = HomeData.normalize(rawName);
        HomeResult result = Sethome.data().remove(player.getUUID(), rawName);
        if (result == HomeResult.OK) {
            Sethome.saveHomes();
            message(source, player, ChatFormatting.GREEN + "Home '" + normalized + "' deleted.", true);
            return 1;
        }
        message(source, player, ChatFormatting.RED + "Home '" + normalized + "' not found.", false);
        return 0;
    }

    /** Sends one chat line and records it in the product's single chat sink. */
    private static void message(CommandSourceStack source, ServerPlayer player, String text, boolean success) {
        Component component = Component.literal(text);
        if (player != null) Sethome.chat(player.getUUID(), text, source.getServer().getTickCount());
        if (success) source.sendSuccess(() -> component, false);
        else source.sendFailure(component);
    }
}
