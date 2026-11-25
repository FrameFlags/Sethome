package com.frameflags.sethome;

import com.frameflags.sethome.core.CountdownQueue;
import com.frameflags.sethome.core.HomeConfig;
import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.Pending;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static product API shared by every Sethome cell. The Forge adapter only
 * supplies the game directory and the tick source; all state lives here so the
 * in-game probe can observe it.
 */
public final class Sethome {
    /** Every chat and action-bar message sent by the adapter, in order. */
    public static final List<String> recentMessages =
            Collections.synchronizedList(new ArrayList<String>());

    private static HomeConfig config = new HomeConfig();
    private static HomeData data = new HomeData();
    private static final CountdownQueue queue = new CountdownQueue();
    private static Path gameDir;
    private static long tickCounter;

    private Sethome() {
    }

    public static HomeConfig config() {
        return config;
    }

    public static HomeData data() {
        return data;
    }

    public static CountdownQueue queue() {
        return queue;
    }

    public static Path dataFile() {
        return configDir().resolve("sethome-homes.json");
    }

    public static Path configFile() {
        return configDir().resolve("sethome.json");
    }

    private static Path configDir() {
        return gameDir == null ? Paths.get("config") : gameDir.resolve("config");
    }

    /** Loads config and homes from the given game directory. Safe to call again. */
    public static void init(Path dir) {
        gameDir = dir;
        Path configPath = configFile();
        Path dataPath = dataFile();
        try {
            Files.createDirectories(configPath.getParent());
        } catch (IOException | RuntimeException ignored) {
            // In-memory defaults still work if the directory cannot be created.
        }
        config = HomeConfig.load(configPath);
        String json = "";
        try {
            if (Files.exists(dataPath)) {
                json = new String(Files.readAllBytes(dataPath), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            json = "";
        }
        data = HomeData.fromJson(json);
    }

    /** Persists the exact JSON produced by the data object the commands use. */
    public static void saveHomes() {
        Path file = dataFile();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, data().toJson().getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // Keep the in-memory data even if the disk write fails.
        }
    }

    /** Adapters must call this for every chat/action-bar message they send. */
    public static void recordMessage(String text) {
        recentMessages.add(text);
    }

    /** Monotonically increasing server tick counter (never reset by init). */
    public static long tick() {
        return tickCounter;
    }

    /** Advances the server tick and returns the countdowns that are due. */
    public static List<Pending> advanceTick() {
        return queue().tick(++tickCounter);
    }
}
