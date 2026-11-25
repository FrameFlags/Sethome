package com.frameflags.sethome;

import com.frameflags.sethome.core.CountdownQueue;
import com.frameflags.sethome.core.HomeConfig;
import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.Pending;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared product API. Platform adapters (commands, events) live next to this
 * class; the core package itself is a verbatim copy of shared-core.
 */
public final class Sethome {
    private static HomeConfig config = new HomeConfig();
    private static HomeData data = new HomeData();
    private static final CountdownQueue queue = new CountdownQueue();

    private static Path gameDir;
    private static long tickCounter;

    /** Chat and action-bar messages sent by the adapter, in send order. */
    public static final List<String> recentMessages =
            Collections.synchronizedList(new ArrayList<String>());

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
        return gameDir().resolve("config").resolve("sethome-homes.json");
    }

    public static Path configFile() {
        return gameDir().resolve("config").resolve("sethome.json");
    }

    private static Path gameDir() {
        return gameDir != null ? gameDir : java.nio.file.Paths.get(".").toAbsolutePath().normalize();
    }

    /**
     * Loads config and homes from the given game directory. Safe to call
     * repeatedly (the test probe does); the tick counter is never reset.
     */
    public static synchronized void init(Path dir) {
        gameDir = dir;
        try {
            Files.createDirectories(gameDir().resolve("config"));
        } catch (IOException ignored) {
            // In-memory state still applies.
        }
        config = HomeConfig.load(configFile());
        String json = "";
        try {
            if (Files.exists(dataFile())) {
                json = new String(Files.readAllBytes(dataFile()), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Start with empty homes.
        }
        data = HomeData.fromJson(json);
    }

    /** Persists exactly the data the commands are using. */
    public static synchronized void saveHomes() {
        Path file = dataFile();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, data().toJson().getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // Keep the in-memory copy.
        }
    }

    /** Called by the adapter for every chat/action-bar message. */
    public static void recordMessage(String text) {
        recentMessages.add(text);
    }

    /** Monotonically increasing server tick counter. */
    public static synchronized long tick() {
        return tickCounter;
    }

    /** Advance one server tick and drain due countdown entries. */
    public static synchronized List<Pending> advanceTick() {
        ++tickCounter;
        return queue.tick(tickCounter);
    }
}
