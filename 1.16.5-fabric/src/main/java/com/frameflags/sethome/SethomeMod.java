package com.frameflags.sethome;

import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.HomeRecord;
import com.frameflags.sethome.core.HomeResult;
import com.frameflags.sethome.core.Pending;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Fabric 1.16.5 entrypoint: product API wiring, commands, ticking and cancellations. */
public final class SethomeMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> Sethome.init(FabricLoader.getInstance().getGameDir()));
        CommandRegistrationCallback.EVENT.register(SethomeMod::registerCommands);
        ServerTickEvents.END_SERVER_TICK.register(SethomeMod::onServerTick);

        // Countdown teleports are cancelled on disconnect...
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            if (player != null) Sethome.queue().cancel(player.getUuid());
        });
        // ...and on death.
        ServerPlayerEvents.ALLOW_DEATH.register((player, damageSource, damageTaken) -> {
            Sethome.queue().cancel(player.getUuid());
            return true;
        });
    }


    /** Yarn 1.16.5 declares getPlayer() as throwing when the source is not a player. */
    private static ServerPlayerEntity playerOf(ServerCommandSource source) {
        try {
            return source.getPlayer();
        } catch (CommandSyntaxException ignored) {
            return null;
        }
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, boolean dedicated) {
        // No .requires(...) anywhere: no op, no cheats, no permission node.
        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("sethome")
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.greedyString())
                        .suggests(SethomeMod::suggestHomes)
                        .executes(ctx -> setHome(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> setHome(ctx, "home")));

        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("home")
                .then(LiteralArgumentBuilder.<ServerCommandSource>literal("list")
                        .executes(SethomeMod::listHomes))
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.greedyString())
                        .suggests(SethomeMod::suggestHomes)
                        .executes(ctx -> goHome(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> goHome(ctx, "home")));

        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("delhome")
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> delHome(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> delHome(ctx, "home")));
    }

    /** Brigadier completion offers only the sender's own home names. */
    private static CompletableFuture<Suggestions> suggestHomes(CommandContext<ServerCommandSource> ctx,
                                                               SuggestionsBuilder builder) {
        ServerPlayerEntity player = playerOf(ctx.getSource());
        if (player != null) {
            for (String home : Sethome.data().list(player.getUuid())) {
                builder.suggest(home);
            }
        }
        return builder.buildFuture();
    }

    private static int setHome(CommandContext<ServerCommandSource> ctx, String rawName) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = playerOf(source);
        if (player == null) {
            chat(source, "This command must be run by a player.");
            return 0;
        }
        String name = HomeData.normalize(rawName);
        HomeRecord record = new HomeRecord(player.getX(), player.getY(), player.getZ(),
                player.yaw, player.pitch, dimensionKey(player));
        HomeResult result = Sethome.data().set(player.getUuid(), rawName, record, Sethome.config().maxHomes);
        switch (result) {
            case OK:
                Sethome.saveHomes();
                chat(source, "Set home '" + name + "' at " + record.format() + ".");
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
                return 0;
        }
    }

    private static int goHome(CommandContext<ServerCommandSource> ctx, String rawName) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = playerOf(source);
        if (player == null) {
            chat(source, "This command must be run by a player.");
            return 0;
        }
        UUID uuid = player.getUuid();
        String name = HomeData.normalize(rawName);
        HomeRecord record = Sethome.data().get(uuid, rawName);
        if (record == null) {
            chat(source, "No home named '" + name + "'.");
            return 0;
        }
        int seconds = Sethome.config().countdownSeconds;
        if (seconds > 0) {
            Sethome.queue().schedule(uuid, name, Sethome.tick() + (long) seconds * 20L);
            chat(source, "Teleporting to '" + name + "' in " + seconds + "s...");
        } else {
            teleport(player, record);
            chat(source, "Teleported to '" + name + "'.");
        }
        return 1;
    }

    private static int listHomes(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = playerOf(source);
        if (player == null) {
            chat(source, "This command must be run by a player.");
            return 0;
        }
        List<String> names = Sethome.data().list(player.getUuid());
        StringBuilder sb = new StringBuilder("Homes (").append(names.size()).append("):");
        for (String name : names) {
            HomeRecord record = Sethome.data().get(player.getUuid(), name);
            sb.append('\n').append(name).append(" - ").append(record == null ? "?" : record.format());
        }
        // Exactly one chat component, possibly multi-line.
        chat(source, sb.toString());
        return names.size();
    }

    private static int delHome(CommandContext<ServerCommandSource> ctx, String rawName) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = playerOf(source);
        if (player == null) {
            chat(source, "This command must be run by a player.");
            return 0;
        }
        String name = HomeData.normalize(rawName);
        HomeResult result = Sethome.data().remove(player.getUuid(), rawName);
        if (result == HomeResult.OK) {
            Sethome.saveHomes();
            chat(source, "Deleted home '" + name + "'.");
            return 1;
        }
        chat(source, "No home named '" + name + "'.");
        return 0;
    }

    private static void onServerTick(MinecraftServer server) {
        List<Pending> due = Sethome.advanceTick();
        long now = Sethome.tick();

        // Action-bar countdown: no chat messages per tick. The [ACTIONBAR]
        // marker lets the probe ignore these when checking for chat spam.
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Pending pending = Sethome.queue().get(player.getUuid());
            if (pending != null) {
                long remaining = Math.max(0L, pending.deadlineTick - now);
                if ((remaining % 10L) == 0L) {
                    actionBar(player, "Teleporting to '" + pending.homeName + "' in "
                            + (remaining / 20L) + "s");
                }
            }
        }

        for (Pending pending : due) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(pending.player);
            if (player == null) continue;
            // Re-resolve: the home may have been deleted while queued.
            HomeRecord record = Sethome.data().get(pending.player, pending.homeName);
            if (record == null) {
                chat(player.getCommandSource(), "Home '" + pending.homeName + "' no longer exists.");
                continue;
            }
            teleport(player, record);
            chat(player.getCommandSource(), "Teleported to '" + pending.homeName + "'.");
        }
    }

    /** Raw doubles, no +0.5: the probe asserts exact stored pose restoration. */
    private static void teleport(ServerPlayerEntity player, HomeRecord record) {
        player.setPos(record.x, record.y, record.z);
        player.yaw = record.yaw;
        player.pitch = record.pitch;
    }

    private static String dimensionKey(ServerPlayerEntity player) {
        try {
            return player.world.getRegistryKey().getValue().toString();
        } catch (RuntimeException ignored) {
            return "minecraft:overworld";
        }
    }

    private static void chat(ServerCommandSource source, String text) {
        Sethome.recordMessage(text);
        source.sendFeedback(new LiteralText(text), false);
    }

    private static void actionBar(ServerPlayerEntity player, String text) {
        String marked = "[ACTIONBAR] " + text;
        Sethome.recordMessage(marked);
        // Yarn 1.16.5 leaves MessageType constants unnamed; index 2 is GAME_INFO.
        MessageType[] types = MessageType.values();
        MessageType type = types.length > 2 ? types[2] : types[0];
        player.sendMessage(new LiteralText(marked), type, player.getUuid());
    }
}
