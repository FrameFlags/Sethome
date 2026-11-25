package com.frameflags.sethome.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class HomeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int maxHomes = -1;
    public int countdownSeconds = 0;

    public static HomeConfig load(Path file) {
        if (!Files.exists(file)) {
            HomeConfig config = new HomeConfig();
            config.save(file);
            return config;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            HomeConfig config = GSON.fromJson(json, HomeConfig.class);
            return config == null ? new HomeConfig() : config;
        } catch (IOException | RuntimeException e) {
            return new HomeConfig();
        }
    }

    public void save(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // The in-memory config still applies for this session.
        }
    }
}
