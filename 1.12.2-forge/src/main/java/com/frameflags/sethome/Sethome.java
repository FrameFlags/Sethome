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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static product API shared by the commands and the tick handler.
 *
 * <p>The test-only probe reads this class through reflection only, so the probe jar
 * still loads when this class is absent (the CI negative control removes the product jar).
 */
public final class Sethome {
    /** Chat and action-bar messages sent by this mod, newest last. Never null. */
    public static final List<String> recentMessages = Collections.synchronizedList(new ArrayList<String>());

    /** Prefix used when recording action-bar messages; the probe filters these out of chat-spam checks. */
    public static final String ACTION_BAR_MARKER = "[action-bar] ";

    private static final AtomicLong TICK_COUNTER = new AtomicLong();

    private static HomeConfig config;
    private static HomeData data;
    private static CountdownQueue queue;
    private static Path gameDir;
    private static Path configFile = Paths.get("config", "sethome.json");
    private static Path dataFile = Paths.get("config", "sethome-homes.json");

    private Sethome() {
    }

    public static HomeConfig config() {
        if (config == null) {
            config = HomeConfig.load(configFile());
        }
        return config;
    }

    public static HomeData data() {
        if (data == null) {
            data = HomeData.fromJson(read(dataFile()));
        }
        return data;
    }

    public static CountdownQueue queue() {
        if (queue == null) {
            queue = new CountdownQueue();
        }
        return queue;
    }

    public static Path dataFile() {
        return dataFile;
    }

    public static Path configFile() {
        return configFile;
    }

    /**
     * Points storage at {@code <gameDir>/config}, creates that directory, then reloads
     * config and homes. Safe to call more than once; the tick counter stays monotonic.
     */
    public static synchronized void init(Path newGameDir) {
        if (newGameDir == null) {
            newGameDir = Paths.get(".").toAbsolutePath().normalize();
        }
        gameDir = newGameDir.toAbsolutePath().normalize();
        configFile = gameDir.resolve("config").resolve("sethome.json");
        dataFile = gameDir.resolve("config").resolve("sethome-homes.json");
        try {
            Files.createDirectories(configFile.getParent());
        } catch (IOException ignored) {
            // Storage writes silently fall back to in-memory state.
        }
        config = HomeConfig.load(configFile);
        data = HomeData.fromJson(read(dataFile));
        if (queue == null) {
            queue = new CountdownQueue();
        }
    }

    /** Persists the in-memory home table to {@link #dataFile()}. */
    public static void saveHomes() {
        Path file = dataFile();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, data().toJson().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (RuntimeException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // The in-memory data still applies for this session.
        }
    }

    /** Appends a message so the probe can count what players actually received. */
    public static void recordMessage(String text) {
        if (text != null) recentMessages.add(text);
    }

    /** Monotonically increasing server tick counter (never reset by init). */
    public static long tick() {
        return TICK_COUNTER.get();
    }

    /** Advances the tick and drains every countdown that is now due. */
    public static List<Pending> advanceTick() {
        return queue().tick(TICK_COUNTER.incrementAndGet());
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
