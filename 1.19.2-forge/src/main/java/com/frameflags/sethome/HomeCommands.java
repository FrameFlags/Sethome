package com.frameflags.sethome;

import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.HomeRecord;
import com.frameflags.sethome.core.HomeResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Registers the three Brigadier commands used by the probe. No .requires(...). */
public final class HomeCommands {
    private HomeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sethome")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> setHome(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> setHome(ctx.getSource(), "home")));

        dispatcher.register(Commands.literal("home")
                // "list" must be registered before the greedy name argument so that
                // Brigadier resolves /home list as the literal sub-command.
                .then(Commands.literal("list").executes(ctx -> listHomes(ctx.getSource())))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                                for (String name : Sethome.data().list(player.getUUID())) {
                                    builder.suggest(name);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> home(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> home(ctx.getSource(), "home")));

        dispatcher.register(Commands.literal("delhome")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> delHome(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> delHome(ctx.getSource(), "home")));
    }

    public static int setHome(CommandSourceStack source, String rawName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = HomeData.normalize(rawName);
        HomeRecord record = new HomeRecord(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), dimensionKey(player));
        HomeResult result = Sethome.data().set(player.getUUID(), rawName, record, Sethome.config().maxHomes);
        switch (result) {
            case OK:
                Sethome.saveHomes();
                chat(source, "Home '" + name + "' set at " + record.format() + ".");
                return 1;
            case RESERVED_NAME:
                chat(source, "'list' is a reserved home name.");
                return 0;
            case INVALID_NAME:
                chat(source, "Invalid home name.");
                return 0;
            case LIMIT_REACHED:
                chat(source, "Home limit reached (" + Sethome.config().maxHomes + " homes).");
                return 0;
            default:
                chat(source, "Could not set home.");
                return 0;
        }
    }

    public static int home(CommandSourceStack source, String rawName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = HomeData.normalize(rawName);
        HomeRecord record = Sethome.data().get(player.getUUID(), name);
        if (record == null) {
            chat(source, "No home named '" + name + "'.");
            return 0;
        }
        int seconds = Sethome.config().countdownSeconds;
        if (seconds > 0) {
            Sethome.queue().schedule(player.getUUID(), name, Sethome.tick() + (long) seconds * 20L);
            chat(source, "Teleporting to home '" + name + "' in " + seconds + "s.");
            return 1;
        }
        teleport(player, record);
        chat(source, "Teleported to home '" + name + "'.");
        return 1;
    }

    public static int listHomes(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        java.util.List<String> names = Sethome.data().list(player.getUUID());
        if (names.isEmpty()) {
            chat(source, "No homes set.");
            return 1;
        }
        StringBuilder out = new StringBuilder("Homes: ");
        for (String name : names) {
            HomeRecord record = Sethome.data().get(player.getUUID(), name);
            if (out.length() > "Homes: ".length()) out.append(", ");
            out.append(name).append(" (").append(record == null ? "?" : record.format()).append(")");
        }
        // Exactly one chat message, even for many homes.
        chat(source, out.toString());
        return 1;
    }

    public static int delHome(CommandSourceStack source, String rawName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = HomeData.normalize(rawName);
        HomeResult result = Sethome.data().remove(player.getUUID(), rawName);
        if (result == HomeResult.OK) {
            Sethome.saveHomes();
            chat(source, "Deleted home '" + name + "'.");
            return 1;
        }
        chat(source, "No home named '" + name + "'.");
        return 0;
    }

    /** Same-dimension teleport to the raw stored doubles (no +0.5 fudge). */
    public static void teleport(ServerPlayer player, HomeRecord record) {
        player.setPos(record.x, record.y, record.z);
        player.setYRot(record.yaw);
        player.setXRot(record.pitch);
    }

    public static String dimensionKey(ServerPlayer player) {
        return player.getLevel().dimension().location().toString();
    }

    /** Sends one chat message and records it for the probe. */
    public static void chat(CommandSourceStack source, String text) {
        source.sendSuccess(Component.literal(text), false);
        Sethome.recordMessage(text);
    }

    public static void chat(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
        Sethome.recordMessage(text);
    }

    /** Action-bar countdown messages are recorded with a marker so the probe
     *  can exclude them from chat-spam counts. */
    public static void actionBar(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), true);
        Sethome.recordMessage("[ACTIONBAR] " + text);
    }
}
