package com.frameflags.sethome;

import com.frameflags.sethome.core.CountdownQueue;
import com.frameflags.sethome.core.HomeConfig;
import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.HomeRecord;
import com.frameflags.sethome.core.Pending;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Static product API shared by every cell's command adapter and tick hook. */
public final class Sethome {
    private static final HomeConfig CONFIG = new HomeConfig();
    private static HomeData data = new HomeData();
    private static final CountdownQueue QUEUE = new CountdownQueue();
    private static final Map<UUID, List<String>> CHAT = new ConcurrentHashMap<>();
    private static Path gameDir = Path.of(".");

    private Sethome() {
    }

    public static HomeConfig config() {
        return CONFIG;
    }

    public static HomeData data() {
        return data;
    }

    public static CountdownQueue queue() {
        return QUEUE;
    }

    public static Path dataFile() {
        return gameDir.resolve("config").resolve("sethome-homes.json");
    }

    public static Path configFile() {
        return gameDir.resolve("config").resolve("sethome.json");
    }

    /** Loads config and homes once; creates parent directories. */
    public static synchronized void init(Path dir) {
        gameDir = dir == null ? Path.of(".") : dir.toAbsolutePath();
        try {
            Files.createDirectories(gameDir.resolve("config"));
            HomeConfig loaded = HomeConfig.load(configFile());
            CONFIG.maxHomes = loaded.maxHomes;
            CONFIG.countdownSeconds = loaded.countdownSeconds;
        } catch (Throwable ignored) {
            // Defaults stay in effect for this session.
        }
        try {
            if (Files.exists(dataFile())) {
                data = HomeData.fromJson(Files.readString(dataFile(), StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // Keep empty homes rather than crashing startup.
        }
    }

    /** Writes exactly the data the commands use. */
    public static synchronized void saveHomes() {
        try {
            Path file = dataFile();
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, data.toJson().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException fallback) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) {
            // In-memory state is authoritative for this session.
        }
    }

    /** Single chat sink: every chat line sent by this product is recorded for the no-spam probe. */
    public static void chat(UUID player, String text, int tick) {
        if (player == null) return;
        List<String> log = CHAT.computeIfAbsent(player, key -> new ArrayList<>());
        synchronized (log) {
            log.add(tick + "|" + text);
            while (log.size() > 256) log.remove(0);
        }
    }

    public static List<String> chatLog(UUID player) {
        List<String> log = CHAT.get(player);
        if (log == null) return List.of();
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    public static void clearChatLog(UUID player) {
        CHAT.remove(player);
    }

    public static void cancel(UUID player) {
        QUEUE.cancel(player);
    }

    /** Drives countdown deadlines once per server tick using the authoritative tick clock. */
    public static void onServerTick(MinecraftServer server, int tick) {
        if (server == null) return;
        List<Pending> due = QUEUE.tick(tick);
        for (Pending pending : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player);
            if (player == null || player.isDeadOrDying()) continue; // death / disconnect cancels
            HomeRecord record = data.get(pending.player, pending.homeName);
            if (record == null) {
                chat(player.getUUID(), ChatFormatting.RED + "Home '" + pending.homeName + "' no longer exists.", tick);
                player.sendSystemMessage(Component.literal(ChatFormatting.RED + "Home '" + pending.homeName + "' no longer exists."));
                continue;
            }
            teleport(player, record, server);
            String done = ChatFormatting.GREEN + "Teleported to home '" + pending.homeName + "'.";
            chat(player.getUUID(), done, tick);
            player.sendSystemMessage(Component.literal(done));
        }
        // Countdown display is action bar only - never one chat line per tick.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Pending pending = QUEUE.get(player.getUUID());
            if (pending == null || player.isDeadOrDying()) {
                if (pending != null) QUEUE.cancel(player.getUUID());
                continue;
            }
            long remaining = pending.deadlineTick - tick;
            if (remaining > 0) {
                long seconds = (remaining + 19L) / 20L;
                player.sendSystemMessage(Component.literal("Teleport to " + pending.homeName + " in " + seconds + "s"), true);
            }
        }
    }

    /** Teleports to the saved pose. Stored coords are offset by -0.5 so +0.5 restores the exact pose. */
    public static void teleport(ServerPlayer player, HomeRecord record, MinecraftServer server) {
        ServerLevel level = null;
        Identifier dim = Identifier.tryParse(record.dimension);
        if (dim != null) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dim);
            level = server.getLevel(key);
        }
        if (level == null) level = (ServerLevel) player.level();
        player.teleportTo(level, record.x + 0.5D, record.y + 0.5D, record.z + 0.5D, java.util.Set.of(), record.yaw, record.pitch, false);
    }
}
