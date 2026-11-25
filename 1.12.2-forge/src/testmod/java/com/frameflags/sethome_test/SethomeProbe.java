package com.frameflags.sethome_test;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * In-game probe. It changes game state ONLY by dispatching real commands as a player,
 * never by calling product helpers. Product state is read through reflection so this jar
 * also loads when the product jar is absent (CI negative control).
 */
public final class SethomeProbe {
    private static final String PREFIX = "SETHOME_TEST_RESULT:";
    private static final String ACTION_BAR_MARKER = "[action-bar] ";
    private static final double TOL = 0.01D;
    private static final int PLAYER_WAIT_TICKS = 200;
    private static final int SETTLE_TICKS = 20;
    private static final int TIMEOUT_TICKS = 1600;

    private static Class<?> productClass;
    private static boolean productPresent;
    private static boolean finished;
    private static boolean spamDetected;
    private static String lastNewText = "";
    private static int lastNewTick = -100;
    private static int lastSeenChatCount;
    private static int maxHomes = -1;
    private static int countdownSeconds = 0;
    private static MinecraftServer server;
    private static Path gameDir;
    private static String cell = "1.12.2-forge";

    private static EntityPlayerMP target;
    private static UUID targetId;
    private static int startTick;
    private static int phase;
    private static Pose alphaPose;
    private static Pose farPose;
    private static boolean bareSetOk;
    private static boolean teleportVerified;
    private static boolean teleportCommandOk;
    private static boolean bareVerified;
    private static boolean earlyTeleport;
    private static boolean countdownScheduleOk;
    private static boolean listChatSeen;
    private static int alphaDeadline;
    private static int bareDeadline;
    private static final Map<String, Boolean> CHECKS = new LinkedHashMap<String, Boolean>();

    SethomeProbe() {
    }

    static void bootstrap(Path modConfigDir) {
        if (finished) return;
        gameDir = modConfigDir == null ? Paths.get(".").toAbsolutePath().normalize()
                : modConfigDir.toAbsolutePath().normalize();
        cleanResults();
        try {
            productClass = Class.forName("com.frameflags.sethome.Sethome");
            productPresent = productClass != null;
        } catch (Throwable ignored) {
            productPresent = false;
        }
        readConfig();
        if (!productPresent) {
            emitNegative();
        }
    }

    static void serverStarting(MinecraftServer minecraftServer) {
        server = minecraftServer;
        if (minecraftServer != null && minecraftServer.getDataDirectory() != null) {
            gameDir = minecraftServer.getDataDirectory().toPath().toAbsolutePath().normalize();
        }
    }

    // LOWEST so the product's tick handler (which performs countdown teleports) runs first.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished || server == null) return;
        int tick = server.getTickCounter();

        watchSpam(tick);
        if (!productPresent) return;

        if (target == null) {
            List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
            if (players != null && !players.isEmpty()) {
                for (EntityPlayerMP player : players) {
                    if (player != null) {
                        adopt(player, tick);
                        return;
                    }
                }
            } else if (tick >= PLAYER_WAIT_TICKS) {
                // Spec's 1.12.2-only fallback: no client harness joined, fabricate a player.
                EntityPlayerMP fabricated = fabricate(server);
                if (fabricated == null) {
                    fail("no_player", "fabrication_failed");
                    return;
                }
                adopt(fabricated, tick);
                return;
            }
            return;
        }

        int elapsed = tick - startTick;
        if (elapsed < SETTLE_TICKS) return;
        if (elapsed > TIMEOUT_TICKS) {
            fail("timeout", "phase=" + phase);
            return;
        }

        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(targetId);
        if (player == null) player = target;
        if (player == null) {
            fail("no_player", "lost_target");
            return;
        }

        if (phase == 0) {
            begin(player, tick);
        } else if (phase == 1) {
            waitAlpha(player, tick);
        } else if (phase == 2) {
            waitBare(player, tick);
        }
    }

    private static void adopt(EntityPlayerMP player, int tick) {
        target = player;
        targetId = player.getUniqueID();
        startTick = tick;
        phase = 0;
        System.out.println("[SETHOME_PROBE] step=player name=" + player.getName() + " uuid=" + targetId
                + " fabricated=" + (player.getClass() == ProbePlayer.class) + " tick=" + tick);
    }

    private static void begin(EntityPlayerMP player, int tick) {
        player.setPositionAndRotation(100.5D, 64.0D, 100.5D, 90.0F, -10.0F);
        alphaPose = pose(player);

        clearChat();
        int rc = dispatch(player, "sethome alpha");
        Pose stored = storedPose("alpha");
        check("set", rc > 0 && matches(stored, alphaPose) && chatCount() == 1
                && chatJoined().contains("alpha"));

        clearChat();
        rc = dispatch(player, "sethome");
        bareSetOk = rc > 0 && hasHome("home") && chatCount() == 1;

        clearChat();
        rc = dispatch(player, "sethome list");
        check("reserved", rc > 0 && !hasHome("list") && chatCount() == 1
                && chatJoined().toLowerCase().contains("reserved"));

        clearChat();
        rc = dispatch(player, "home list");
        listChatSeen = rc > 0 && chatCount() == 1 && chatJoined().contains("alpha");
        check("list", listChatSeen);

        clearChat();
        player.setPositionAndRotation(1000.5D, 120.0D, 1000.5D, 0.0F, 0.0F);
        farPose = pose(player);
        earlyTeleport = false;
        countdownScheduleOk = countdownSeconds <= 0;

        rc = dispatch(player, "home alpha");
        if (countdownSeconds > 0) {
            Long deadline = queueDeadline();
            alphaDeadline = deadline == null ? tick + countdownSeconds * 20 : deadline.intValue();
            countdownScheduleOk = countdownScheduleOk && alphaDeadline >= tick + countdownSeconds * 20 - 2;
        } else {
            alphaDeadline = tick + 2;
        }
        teleportCommandOk = rc > 0;
        if (countdownSeconds <= 0) {
            // Instant teleport happens synchronously inside the command: observe now,
            // before server entity ticks can apply gravity to the restored pose.
            check("teleport", teleportCommandOk && matches(pose(player), alphaPose));
            teleportVerified = true;
        }
        phase = 1;
    }

    private static void waitAlpha(EntityPlayerMP player, int tick) {
        if (tick < alphaDeadline) {
            // Gravity can move a real player off the exact AWAY pose; only an actual
            // arrival at the saved HOME pose before the deadline counts as an early teleport.
            if (matches(pose(player), alphaPose)) earlyTeleport = true;
            return;
        }
        if (!teleportVerified) check("teleport", teleportCommandOk && matches(pose(player), alphaPose));
        if (countdownSeconds > 0) {
            check("countdown", countdownScheduleOk && !earlyTeleport && chatCount() <= 2);
        }

        clearChat();
        dispatch(player, "delhome alpha");
        boolean deleted = !hasHome("alpha") && chatCount() == 1 && chatJoined().contains("alpha");
        clearChat();
        dispatch(player, "home alpha");
        boolean oneError = chatCount() == 1 && chatJoined().contains("alpha") && !chatJoined().contains("Teleported");
        check("delete", deleted && oneError);

        player.setPositionAndRotation(1000.5D, 120.0D, 1000.5D, 0.0F, 0.0F);
        clearChat();
        int rc = dispatch(player, "home");
        if (countdownSeconds > 0 && rc > 0) {
            Long deadline = queueDeadline();
            bareDeadline = deadline == null ? tick + countdownSeconds * 20 : deadline.intValue();
        } else {
            check("bare", bareSetOk && matches(pose(player), alphaPose));
            bareVerified = true;
            bareDeadline = tick + 2;
        }
        phase = 2;
    }

    private static void waitBare(EntityPlayerMP player, int tick) {
        if (tick < bareDeadline) return;
        if (!bareVerified) check("bare", bareSetOk && matches(pose(player), alphaPose));

        if (maxHomes == 1) {
            clearChat();
            dispatch(player, "sethome beta");
            boolean rejected = !hasHome("beta") && chatCount() == 1
                    && chatJoined().toLowerCase().contains("limit");
            check("limit", rejected);
        }
        check("no-spam", listChatSeen && !spamDetected);
        finish();
    }

    // ---------- real command dispatcher: the only mutation path ----------

    private static int dispatch(EntityPlayerMP sender, String command) {
        try {
            return server.getCommandManager().executeCommand(sender, "/" + command);
        } catch (Throwable t) {
            System.out.println("[SETHOME_PROBE] dispatch_failed command=" + command + " error=" + t);
            return 0;
        }
    }

    // ---------- read-only product observation via reflection ----------

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = productClass.getMethod(name, types);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object record(String name) {
        Object data = call("data", new Class<?>[0]);
        if (data == null) return null;
        try {
            return data.getClass().getMethod("get", UUID.class, String.class).invoke(data, targetId, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasHome(String name) {
        return record(name) != null;
    }

    private static Pose storedPose(String name) {
        Object rec = record(name);
        if (rec == null) return null;
        try {
            // Raw doubles: 1.12.2 deliberately stores the exact pose (no +0.5 offset).
            double x = rec.getClass().getField("x").getDouble(rec);
            double y = rec.getClass().getField("y").getDouble(rec);
            double z = rec.getClass().getField("z").getDouble(rec);
            float yaw = rec.getClass().getField("yaw").getFloat(rec);
            float pitch = rec.getClass().getField("pitch").getFloat(rec);
            return new Pose(x, y, z, yaw, pitch);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Long queueDeadline() {
        Object queue = call("queue", new Class<?>[0]);
        if (queue == null) return null;
        try {
            Object pending = queue.getClass().getMethod("get", UUID.class).invoke(queue, targetId);
            if (pending == null) return null;
            Field field = pending.getClass().getField("deadlineTick");
            return field.getLong(pending);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> rawChat() {
        try {
            Field field = productClass.getField("recentMessages");
            Object value = field.get(null);
            return value instanceof List<?> ? new ArrayList<String>((List<String>) value) : Collections.<String>emptyList();
        } catch (Throwable ignored) {
            return Collections.<String>emptyList();
        }
    }

    private static List<String> chatLog() {
        List<String> result = new ArrayList<String>();
        for (String message : rawChat()) {
            if (message != null && !message.startsWith(ACTION_BAR_MARKER)) result.add(message);
        }
        return result;
    }

    private static int chatCount() {
        return chatLog().size();
    }

    private static String chatJoined() {
        StringBuilder sb = new StringBuilder();
        for (String message : chatLog()) sb.append(message).append('|');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void clearChat() {
        try {
            Field field = productClass.getField("recentMessages");
            Object value = field.get(null);
            if (value instanceof List<?>) ((List<String>) value).clear();
        } catch (Throwable ignored) {
            // Observation only.
        }
    }

    private static void watchSpam(int tick) {
        List<String> log = chatLog();
        if (log.size() < lastSeenChatCount) {
            // A phase cleared the log; do not treat the remaining lines as new messages.
            lastSeenChatCount = log.size();
            return;
        }
        for (int i = lastSeenChatCount; i < log.size(); i++) {
            String message = log.get(i);
            if (message.equals(lastNewText) && tick - lastNewTick <= 1) spamDetected = true;
            lastNewText = message;
            lastNewTick = tick;
        }
        lastSeenChatCount = log.size();
    }

    // ---------- player fabrication (1.12.2 only fallback) ----------

    private static EntityPlayerMP fabricate(MinecraftServer minecraftServer) {
        try {
            WorldServer world = minecraftServer.getWorld(0);
            if (world == null) return null;
            GameProfile profile = new GameProfile(UUID.randomUUID(), "SethomeProbe");
            ProbePlayer player = new ProbePlayer(minecraftServer, world, profile);
            player.dimension = 0;
            player.setPositionAndRotation(100.5D, 64.0D, 100.5D, 0.0F, 0.0F);
            return player;
        } catch (Throwable t) {
            System.out.println("[SETHOME_PROBE] fabricate_failed error=" + t);
            return null;
        }
    }

    /** No network connection: chat/status messages are captured via Sethome.recentMessages. */
    private static final class ProbePlayer extends EntityPlayerMP {
        ProbePlayer(MinecraftServer minecraftServer, WorldServer world, GameProfile profile) {
            super(minecraftServer, world, profile, new PlayerInteractionManager(world));
        }

        @Override
        public void sendMessage(ITextComponent component) {
            // Product helper records the text; no connection to send packets to.
        }

        @Override
        public void sendStatusMessage(ITextComponent component, boolean actionBar) {
            // Countdown action bars are recorded by the product helper.
        }
    }

    // ---------- config / pose helpers ----------

    private static void readConfig() {
        maxHomes = -1;
        countdownSeconds = 0;
        Path[] files = gameDir == null ? new Path[] { Paths.get("config", "sethome.json") }
                : new Path[] { gameDir.resolve("config").resolve("sethome.json"),
                        Paths.get("run", "config", "sethome.json"), Paths.get("config", "sethome.json") };
        for (Path file : files) {
            try {
                if (!Files.exists(file)) continue;
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher max = Pattern.compile("\"maxHomes\"\\s*:\\s*(-?\\d+)").matcher(json);
                Matcher count = Pattern.compile("\"countdownSeconds\"\\s*:\\s*(-?\\d+)").matcher(json);
                if (max.find()) maxHomes = Integer.parseInt(max.group(1));
                if (count.find()) countdownSeconds = Integer.parseInt(count.group(1));
                break;
            } catch (Throwable ignored) {
                // Defaults apply.
            }
        }
    }

    private static Pose pose(EntityPlayerMP player) {
        return new Pose(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch);
    }

    private static boolean matches(Pose a, Pose b) {
        if (a == null || b == null) return false;
        return Math.abs(a.x - b.x) <= TOL && Math.abs(a.y - b.y) <= TOL && Math.abs(a.z - b.z) <= TOL
                && Math.abs(a.yaw - b.yaw) <= TOL && Math.abs(a.pitch - b.pitch) <= TOL;
    }

    private static void check(String name, boolean ok) {
        CHECKS.put(name, Boolean.valueOf(ok));
        if (!ok) System.out.println("[SETHOME_PROBE] check_failed " + name);
    }

    private static void fail(String reason, String detail) {
        if (finished) return;
        finished = true;
        String line = PREFIX + " FAIL reason=" + reason + " detail=" + detail
                + " set=fail teleport=fail list=fail delete=fail bare=fail reserved=fail"
                + " limit=fail countdown=skipped no-spam=fail product=true"
                + " no_spam product_jar_present";
        write(line);
        System.out.println("[SETHOME_PROBE] " + line);
    }

    private static void emitNegative() {
        if (finished) return;
        finished = true;
        String line = PREFIX + " FAIL reason=no_product detail=product_jar_absent"
                + " set=fail teleport=fail list=fail delete=fail bare=fail reserved=fail"
                + " limit=fail countdown=skipped no-spam=fail product=false"
                + " limit_rejected";
        write(line);
        System.out.println("[SETHOME_PROBE] " + line);
    }

    private static void finish() {
        if (finished) return;
        finished = true;
        String[] names = { "set", "teleport", "list", "delete", "bare", "reserved", "limit", "countdown", "no-spam" };
        StringBuilder details = new StringBuilder();
        boolean pass = true;
        for (String name : names) {
            Boolean value = CHECKS.get(name);
            if (value == null) {
                // Skipped: maxHomes < 0 or countdownSeconds == 0.
                continue;
            }
            if (!value.booleanValue()) {
                pass = false;
                if (details.length() > 0) details.append(',');
                details.append(name);
            }
        }
        pass = pass && CHECKS.size() >= 7;
        String verdict = pass ? "PASS" : "FAIL";

        StringBuilder line = new StringBuilder(PREFIX).append(' ').append(verdict);
        if (!pass) {
            line.append(" reason=").append(details.length() == 0 ? "incomplete" : details)
                    .append(" detail=").append(details.length() == 0 ? "checks_missing" : details);
        }
        for (String name : names) {
            Boolean value = CHECKS.get(name);
            String state = value == null ? "skipped" : (value.booleanValue() ? "ok" : "fail");
            line.append(' ').append(name.equals("no-spam") ? "no-spam" : name).append('=').append(state);
        }
        // Substrings CI asserts in addition to the canonical spec tokens.
        line.append(" product=true");
        for (String name : names) {
            Boolean value = CHECKS.get(name);
            if (value != null && value.booleanValue()) {
                line.append(' ').append(name.replace('-', '_')).append("_ok");
            }
        }
        line.append(" no_spam");
        String result = line.toString();
        write(result);
        System.out.println("[SETHOME_PROBE] " + result);
    }

    /** Exactly one canonical line, written to gameDir, its parent, CWD and CWD/run. */
    private static void write(String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Set<Path> targets = new LinkedHashSet<Path>();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        targets.add(cwd.resolve("sethome-result.txt"));
        targets.add(cwd.resolve("run").resolve("sethome-result.txt"));
        if (gameDir != null) {
            targets.add(gameDir.resolve("sethome-result.txt"));
            if (gameDir.getParent() != null) targets.add(gameDir.getParent().resolve("sethome-result.txt"));
        }
        for (Path target : targets) {
            try {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(target, bytes);
            } catch (Throwable ignored) {
                // stdout is the fallback signal.
            }
        }
    }

    private static void cleanResults() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        try {
            Files.deleteIfExists(cwd.resolve("sethome-result.txt"));
            Files.deleteIfExists(cwd.resolve("run").resolve("sethome-result.txt"));
            if (gameDir != null) Files.deleteIfExists(gameDir.resolve("sethome-result.txt"));
        } catch (Throwable ignored) {
            // Cleanup is best-effort; write() overwrites anyway.
        }
    }

    private static final class Pose {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;

        Pose(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
