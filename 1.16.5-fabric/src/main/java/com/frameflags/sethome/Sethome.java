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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared product API for the Fabric 1.16.5 cell.
 *
 * <p>The in-game probe uses {@link #recentMessages}, {@link #recordMessage(String)},
 * {@link #tick()} and {@link #advanceTick()} to observe commands and countdowns
 * without reaching into Brigadier internals.</p>
 */
public final class Sethome {
    /** Every chat/action-bar message emitted by the adapters is recorded here. */
    public static final List<String> recentMessages = Collections.synchronizedList(new ArrayList<String>());

    private static HomeConfig config = new HomeConfig();
    private static HomeData data = new HomeData();
    private static final CountdownQueue queue = new CountdownQueue();
    private static Path gameDir = Paths.get(".");
    private static long tickCounter = 0L;

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
        return gameDir.resolve("config").resolve("sethome-homes.json");
    }

    public static Path configFile() {
        return gameDir.resolve("config").resolve("sethome.json");
    }

    /** Monotonically increasing server tick counter (never reset by init). */
    public static long tick() {
        return tickCounter;
    }

    public static void recordMessage(String text) {
        if (text != null) recentMessages.add(text);
    }

    /** Advance the countdown queue by exactly one server tick. */
    public static List<Pending> advanceTick() {
        return queue.tick(++tickCounter);
    }

    /**
     * Load config and homes from the game directory. Safe to call more than once:
     * each call re-reads both files. The tick counter is intentionally untouched.
     */
    public static void init(Path dir) {
        gameDir = dir == null ? Paths.get(".") : dir;
        Path configPath = configFile();
        Path dataPath = dataFile();
        try {
            Path parent = configPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path dataParent = dataPath.getParent();
            if (dataParent != null) Files.createDirectories(dataParent);
        } catch (IOException ignored) {
            // Keep the in-memory state if the filesystem is unavailable.
        }
        config = HomeConfig.load(configPath);
        data = new HomeData();
        if (Files.exists(dataPath)) {
            try {
                String json = new String(Files.readAllBytes(dataPath), StandardCharsets.UTF_8);
                data = HomeData.fromJson(json);
            } catch (IOException | RuntimeException ignored) {
                // Start with empty homes on unreadable data.
            }
        }
    }

    /** Persist the currently in-memory homes to {@link #dataFile()}. */
    public static void saveHomes() {
        try {
            Path path = dataFile();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(path, data().toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // In-memory state remains authoritative for this session.
        }
    }
}
