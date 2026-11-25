package com.frameflags.sethome;

import com.frameflags.sethome.core.HomeRecord;
import com.frameflags.sethome.core.HomeResult;
import com.frameflags.sethome.core.Pending;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Forge 1.16.5 adapter: command registration, server ticks and lifecycle.
 * All state and persistence live in {@link Sethome} / the shared core.
 */
@Mod("sethome")
public class SethomeMod {
    private MinecraftServer server;

    public SethomeMod() {
        // FML lifecycle events use the mod bus; Forge game events use EVENT_BUS.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    private void onServerStarted(FMLServerStartedEvent event) {
        server = event.getServer();
        Sethome.init(event.getServer().getServerDirectory().toPath());
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSource> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("sethome")
                .executes(ctx -> setHome(ctx, "home"))
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> setHome(ctx,
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("home")
                .executes(ctx -> goHome(ctx, "home"))
                .then(Commands.literal("list").executes(SethomeMod::listHomes))
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests(SethomeMod::suggestHomes)
                        .executes(ctx -> goHome(ctx,
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("delhome")
                .executes(ctx -> delHome(ctx, "home"))
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> delHome(ctx,
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;

        for (Pending pending : Sethome.advanceTick()) {
            ServerPlayerEntity player = server.getPlayerList().getPlayer(pending.player);
            if (player == null) continue;
            HomeRecord home = Sethome.data().get(pending.player, pending.homeName);
            if (home == null) {
                chat(player, "Home '" + pending.homeName + "' no longer exists; teleport cancelled.");
                continue;
            }
            teleport(player, home);
            chat(player, "Teleported to '" + pending.homeName + "'.");
        }

        // Countdown is action-bar only; the [ACTION_BAR] marker is for the probe.
        long now = Sethome.tick();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            Pending pending = Sethome.queue().get(player.getUUID());
            if (pending == null) continue;
            long remaining = pending.deadlineTick - now;
            if (remaining < 0) continue;
            String text = "Teleporting to '" + pending.homeName + "' in " + remaining + " tick(s)";
            player.sendMessage(new StringTextComponent(text), ChatType.GAME_INFO, player.getUUID());
            Sethome.recordMessage("[ACTION_BAR] " + text);
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof ServerPlayerEntity) {
            Sethome.queue().cancel(event.getEntityLiving().getUUID());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Sethome.queue().cancel(event.getPlayer().getUUID());
    }

    // -- Command implementations -------------------------------------------

    private static ServerPlayerEntity player(CommandContext<CommandSource> ctx) {
        Entity entity = ctx.getSource().getEntity();
        return entity instanceof ServerPlayerEntity ? (ServerPlayerEntity) entity : null;
    }

    private static String dimensionKey(ServerPlayerEntity player) {
        return player.level.dimension().location().toString();
    }

    private static void chat(ServerPlayerEntity player, String text) {
        player.sendMessage(new StringTextComponent(text), ChatType.CHAT, player.getUUID());
        Sethome.recordMessage(text);
    }

    private static void chat(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendSuccess(new StringTextComponent(text), false);
        Sethome.recordMessage(text);
    }

    private static void teleport(ServerPlayerEntity player, HomeRecord home) {
        player.setPos(home.x, home.y, home.z);
        player.yRot = home.yaw;
        player.xRot = home.pitch;
        player.connection.teleport(home.x, home.y, home.z, home.yaw, home.pitch);
    }

    private static int setHome(CommandContext<CommandSource> ctx, String rawName) {
        ServerPlayerEntity player = player(ctx);
        if (player == null) return 0;
        String name = com.frameflags.sethome.core.HomeData.normalize(rawName);
        HomeRecord record = new HomeRecord(player.getX(), player.getY(), player.getZ(),
                player.yRot, player.xRot, dimensionKey(player));
        HomeResult result = Sethome.data().set(player.getUUID(), name, record,
                Sethome.config().maxHomes);
        switch (result) {
            case OK:
                Sethome.saveHomes();
                chat(ctx, "Home '" + name + "' set at " + record.format() + ".");
                return 1;
            case RESERVED_NAME:
                chat(ctx, "That name is reserved; 'list' cannot be used as a home name.");
                return 0;
            case INVALID_NAME:
                chat(ctx, "Invalid home name.");
                return 0;
            case LIMIT_REACHED:
                chat(ctx, "Home limit reached (" + Sethome.config().maxHomes
                        + "); delete a home first.");
                return 0;
            default:
                return 0;
        }
    }

    private static int goHome(CommandContext<CommandSource> ctx, String rawName) {
        ServerPlayerEntity player = player(ctx);
        if (player == null) return 0;
        String name = com.frameflags.sethome.core.HomeData.normalize(rawName);
        HomeRecord home = Sethome.data().get(player.getUUID(), name);
        if (home == null) {
            chat(ctx, "No home named '" + name + "'.");
            return 0;
        }
        int seconds = Sethome.config().countdownSeconds;
        if (seconds > 0) {
            Sethome.queue().schedule(player.getUUID(), name,
                    Sethome.tick() + (long) seconds * 20L);
            chat(ctx, "Teleport to '" + name + "' queued in " + seconds + "s.");
            return 1;
        }
        // Re-resolve at fire time too: instant path is a single tick.
        teleport(player, home);
        chat(ctx, "Teleported to '" + name + "'.");
        return 1;
    }

    private static int listHomes(CommandContext<CommandSource> ctx) {
        ServerPlayerEntity player = player(ctx);
        if (player == null) return 0;
        List<String> names = Sethome.data().list(player.getUUID());
        StringBuilder text = new StringBuilder("Homes (" + names.size() + "):");
        for (String name : names) {
            HomeRecord home = Sethome.data().get(player.getUUID(), name);
            text.append("\n").append(name).append(" @ ")
                    .append(home == null ? "?" : home.format());
        }
        chat(ctx, text.toString());
        return 1;
    }

    private static int delHome(CommandContext<CommandSource> ctx, String rawName) {
        ServerPlayerEntity player = player(ctx);
        if (player == null) return 0;
        String name = com.frameflags.sethome.core.HomeData.normalize(rawName);
        if (Sethome.data().remove(player.getUUID(), name)
                == com.frameflags.sethome.core.HomeResult.OK) {
            Sethome.saveHomes();
            chat(ctx, "Deleted home '" + name + "'.");
            return 1;
        }
        chat(ctx, "No home named '" + name + "'.");
        return 0;
    }

    private static CompletableFuture<Suggestions> suggestHomes(CommandContext<CommandSource> ctx,
                                                               SuggestionsBuilder builder) {
        ServerPlayerEntity player = player(ctx);
        if (player != null) {
            for (String name : Sethome.data().list(player.getUUID())) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }
}
