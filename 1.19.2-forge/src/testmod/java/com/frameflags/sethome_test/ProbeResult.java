package com.frameflags.sethome_test;

import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/** Writes the one canonical result line, deduplicated across all four locations. */
public final class ProbeResult {
    private static boolean written;

    private ProbeResult() {
    }

    public static synchronized void write(MinecraftServer server, String canonical, String diagnostics) {
        if (written) return;
        written = true;

        Path gameDir = server.getServerDirectory().toPath().toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Set<Path> targets = new LinkedHashSet<>();
        targets.add(gameDir.resolve("sethome-result.txt"));
        Path parent = gameDir.getParent();
        if (parent != null) targets.add(parent.resolve("sethome-result.txt"));
        targets.add(cwd.resolve("sethome-result.txt"));
        Path run = cwd.resolve("run").normalize();
        try {
            Files.createDirectories(run);
        } catch (Exception ignored) {
            // Result writing below still tries every location independently.
        }
        targets.add(run.resolve("sethome-result.txt"));

        byte[] payload = (canonical + "\n").getBytes(StandardCharsets.UTF_8);
        for (Path target : targets) {
            Path file = target.toAbsolutePath().normalize();
            try {
                Path dir = file.getParent();
                if (dir != null) Files.createDirectories(dir);
                Files.write(file, payload);
            } catch (Exception ignored) {
                // Never throw out of a tick handler; other locations still get the line.
            }
        }

        System.out.println("[SETHOME_PROBE] " + canonical);
        if (diagnostics != null && !diagnostics.isEmpty()) {
            System.out.println("[SETHOME_PROBE] " + diagnostics);
        }
        System.out.flush();
    }
}
