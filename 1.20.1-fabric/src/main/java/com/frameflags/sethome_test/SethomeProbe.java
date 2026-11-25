package com.frameflags.sethome_test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * In-game probe. It never calls product helpers to perform actions: every state change goes
 * through the real Brigadier dispatcher as the joined player. Product helpers are read only
 * through reflection (so the probe also loads when the product jar is absent).
 */
public final class SethomeProbe {
    private static final double TOL = 0.01D;
    private static final Path ROOT_RESULT = Path.of("sethome-result.txt");
    private static final Path RUN_RESULT = Path.of("run/sethome-result.txt");

    private static String cell = "unknown";
    private static Class<?> productClass;
    private static boolean productPresent;
    private static boolean finished;
    private static int maxHomes = -1;
    private static int countdownSeconds;

    private static UUID target;
    private static int startTick;
    private static int phase;
    private static int checks;
    private static final List<String> passed = new ArrayList<>();
    private static final List<String> failed = new ArrayList<>();
    private static final List<String> observedChat = new ArrayList<>();

    private static Pose alphaPose;
    private static Pose farPose;
    private static boolean bareSetOk;
    private static boolean bareScheduleOk = true;
    private static boolean earlyTeleport;
    private static boolean countdownScheduleOk;
    private static boolean chatSeen;
    private static int alphaDeadline;
    private static int alphaChatBaseline;

    private SethomeProbe() {
    }

    public static void bootstrap(String cellId) {
        if (finished) return;
        cell = cellId;
        try {
            Files.deleteIfExists(ROOT_RESULT);
            Files.deleteIfExists(RUN_RESULT);
        } catch (Throwable ignored) {
            // Report still goes to stdout if the filesystem is read-only.
        }
        try {
            productClass = Class.forName("com.frameflags.sethome.Sethome");
            productPresent = productClass != null;
        } catch (Throwable ignored) {
            productPresent = false;
        }
        readConfig();
        if (!productPresent) {
            check(false, "no_product");
            finish();
        }
    }

    public static void onJoin(ServerPlayer player, int tick) {
        if (finished || !productPresent || target != null || player == null) return;
        target = player.getUUID();
        startTick = tick;
        clearChat();
    }

    public static void onTick(MinecraftServer server, int tick) {
        if (finished || !productPresent || target == null || server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(target);
        if (player == null) return;
        int elapsed = tick - startTick;
        // The host harness stops the game shortly after the joined player is usable. Start as
        // soon as the entity exists instead of burning 40 ticks of a very small wall-clock window.
        if (elapsed < 5) return;
        if (elapsed > 600) {
            check(false, "timeout");
            finish();
            return;
        }
        if (phase == 0) {
            begin(server, player, tick);
        } else if (phase == 1) {
            waitAlpha(server, player, tick);
        }
    }

    private static void begin(MinecraftServer server, ServerPlayer player, int tick) {
        // Keep the observed pose exact: an unloaded far chunk makes the player fall (and the
        // harness wait forever for the chunk), which fakes an early teleport and burns time.
        player.setNoGravity(true);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.teleportTo(player.serverLevel(), 100.5D, 64.5D, 100.5D, Set.of(), 90.0F, -10.0F);
        alphaPose = pose(player);

        clearChat();
        int rc = dispatch(server, player, "sethome alpha");
        Pose stored = storedPose("alpha");
        check(rc > 0 && stored != null && matches(stored, alphaPose) && chatCount() == 1, "set_ok");

        rc = dispatch(server, player, "sethome");
        bareSetOk = rc > 0 && hasHome("home");

        clearChat();
        rc = dispatch(server, player, "sethome list");
        check(rc == 0 && !hasHome("list") && chatCount() == 1, "reserved_ok");

        clearChat();
        rc = dispatch(server, player, "home list");
        String listLog = String.join("|", chatLog());
        chatSeen = chatCount() > 0;
        check(rc > 0 && chatCount() == 1 && listLog.contains("alpha"), "list_ok");

        clearChat();
        moveAway(player);
        earlyTeleport = false;
        countdownScheduleOk = countdownSeconds <= 0;

        rc = dispatch(server, player, "home alpha");
        if (countdownSeconds > 0) {
            Long deadline = queueDeadline();
            alphaDeadline = deadline == null ? -1 : deadline.intValue();
            countdownScheduleOk = countdownScheduleOk && deadline != null
                    && Math.abs(alphaDeadline - (tick + countdownSeconds * 20)) <= 2;
        } else {
            alphaDeadline = tick;
        }
        if (rc <= 0) check(false, "teleport_ok");
        alphaChatBaseline = chatCount();
        phase = 1;
    }

    /** Moves the player a few blocks away inside the already-loaded chunk, with no gravity. */
    private static void moveAway(ServerPlayer player) {
        player.setNoGravity(true);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.teleportTo(player.serverLevel(), alphaPose.x + 4.0D, alphaPose.y, alphaPose.z,
                Set.of(), alphaPose.yaw, alphaPose.pitch);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        farPose = pose(player);
    }

    private static void waitAlpha(MinecraftServer server, ServerPlayer player, int tick) {
        if (tick < alphaDeadline + 1) {
            // The product tick hook runs before this one, so the arrival at the deadline
            // tick is already visible here. Only a strictly earlier arrival is "early".
            if (tick < alphaDeadline && matches(pose(player), alphaPose)) earlyTeleport = true;
            return;
        }
        boolean arrived = matches(pose(player), alphaPose);
        check(arrived, "teleport_ok");
        int countdownChats = chatCount() - alphaChatBaseline;
        boolean chatOk = countdownSeconds > 0 ? countdownChats >= 1 && countdownChats <= 2 : countdownChats == 0;
        System.out.println("[SETHOME_PROBE] countdown diag schedule=" + countdownScheduleOk + " early=" + earlyTeleport + " chats=" + countdownChats + " secs=" + countdownSeconds);
        check(countdownScheduleOk && !earlyTeleport && chatOk, "countdown_ok");

        clearChat();
        int rc = dispatch(server, player, "delhome alpha");
        boolean deleted = rc > 0 && !hasHome("alpha") && chatCount() == 1;
        clearChat();
        rc = dispatch(server, player, "home alpha");
        boolean oneError = rc == 0 && chatCount() == 1;
        check(deleted && oneError, "delete_ok");

        // Bare /home must schedule the home named "home". Read the pending entry instead of
        // waiting out a second countdown: that alone proves the bare form's routing and keeps
        // the whole check set inside the harness's short wall-clock window.
        moveAway(player);
        clearChat();
        rc = dispatch(server, player, "home");
        boolean stillFar = matches(pose(player), farPose);
        if (countdownSeconds > 0) {
            Object pending = pending();
            String homeName = stringField(pending, "homeName");
            Long deadline = queueDeadline();
            bareScheduleOk = rc > 0 && "home".equals(homeName) && deadline != null
                    && Math.abs(deadline.longValue() - (tick + countdownSeconds * 20L)) <= 2;
        } else {
            bareScheduleOk = rc > 0 && matches(pose(player), alphaPose);
        }
        check(bareSetOk && bareScheduleOk && (countdownSeconds <= 0 || stillFar), "bare_ok");

        if (maxHomes == 1) {
            clearChat();
            rc = dispatch(server, player, "sethome beta");
            check(rc == 0 && !hasHome("beta") && chatCount() == 1, "limit_rejected");
        } else {
            rc = dispatch(server, player, "sethome beta");
            check(rc > 0 && hasHome("beta"), "limit_ok");
            dispatch(server, player, "delhome beta");
        }
        check(chatSeen && noRepeatedChat(), "no_spam");
        finish();
    }

    // ---------- dispatcher: the only way the probe changes game state ----------

    private static int dispatch(MinecraftServer server, ServerPlayer player, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // ---------- read-only observation through reflection ----------

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = productClass.getMethod(name, types);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object homes() {
        return call("data", new Class<?>[0]);
    }

    private static Object record(String name) {
        Object data = homes();
        if (data == null) return null;
        try {
            return data.getClass().getMethod("get", UUID.class, String.class).invoke(data, target, name);
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
            double x = rec.getClass().getField("x").getDouble(rec);
            double y = rec.getClass().getField("y").getDouble(rec);
            double z = rec.getClass().getField("z").getDouble(rec);
            float yaw = rec.getClass().getField("yaw").getFloat(rec);
            float pitch = rec.getClass().getField("pitch").getFloat(rec);
            return new Pose(x + 0.5D, y + 0.5D, z + 0.5D, yaw, pitch);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> chatLog() {
        Object log = call("chatLog", new Class<?>[] { UUID.class }, target);
        return log instanceof List<?> ? (List<String>) log : List.of();
    }

    private static int chatCount() {
        return chatLog().size();
    }

    private static void clearChat() {
        observedChat.addAll(chatLog());
        call("clearChatLog", new Class<?>[] { UUID.class }, target);
    }

    private static Object pending() {
        Object queue = call("queue", new Class<?>[0]);
        if (queue == null) return null;
        try {
            return queue.getClass().getMethod("get", UUID.class).invoke(queue, target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringField(Object object, String name) {
        if (object == null) return null;
        try {
            return (String) object.getClass().getField(name).get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Long queueDeadline() {
        Object pending = pending();
        if (pending == null) return null;
        try {
            return pending.getClass().getField("deadlineTick").getLong(pending);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean noRepeatedChat() {
        List<String> log = new ArrayList<>(observedChat);
        log.addAll(chatLog());
        for (int i = 1; i < log.size(); i++) {
            String previous = log.get(i - 1);
            String current = log.get(i);
            int barPrev = previous.indexOf('|');
            int barCur = current.indexOf('|');
            if (barPrev < 0 || barCur < 0) continue;
            if (previous.substring(barPrev + 1).equals(current.substring(barCur + 1))) {
                long delta = parseLong(current.substring(0, barCur)) - parseLong(previous.substring(0, barPrev));
                if (Math.abs(delta) <= 1) return false;
            }
        }
        return true;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (Throwable ignored) {
            return -999999L;
        }
    }

    private static Pose pose(ServerPlayer player) {
        return new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static boolean matches(Pose a, Pose b) {
        if (a == null || b == null) return false;
        return Math.abs(a.x - b.x) <= TOL && Math.abs(a.y - b.y) <= TOL && Math.abs(a.z - b.z) <= TOL
                && Math.abs(a.yaw - b.yaw) <= TOL && Math.abs(a.pitch - b.pitch) <= TOL;
    }

    private static void readConfig() {
        maxHomes = -1;
        countdownSeconds = 0;
        for (Path file : List.of(Path.of("run/config/sethome.json"), Path.of("config/sethome.json"))) {
            try {
                if (!Files.exists(file)) continue;
                String json = Files.readString(file);
                Matcher max = Pattern.compile("\"maxHomes\"\\s*:\\s*(-?\\d+)").matcher(json);
                Matcher count = Pattern.compile("\"countdownSeconds\"\\s*:\\s*(-?\\d+)").matcher(json);
                if (max.find()) maxHomes = Integer.parseInt(max.group(1));
                if (count.find()) countdownSeconds = Integer.parseInt(count.group(1));
                break;
            } catch (Throwable ignored) {
                // Fall through with defaults.
            }
        }
    }

    // ---------- exactly one canonical result line ----------

    private static void check(boolean ok, String token) {
        checks++;
        if (ok) passed.add(token);
        else failed.add(token);
    }

    private static synchronized void finish() {
        if (finished) return;
        finished = true;
        String verdict = failed.isEmpty() && checks >= 9 ? "PASS" : "FAIL";
        StringBuilder line = new StringBuilder("SETHOME_TEST_RESULT: ").append(verdict)
                .append(" cell=").append(cell)
                .append(" product=").append(productPresent)
                .append(" checks=").append(checks)
                .append(" failures=").append(failed.size());
        if (!failed.isEmpty()) line.append(" reason=").append(String.join(",", failed));
        for (String token : passed) line.append(' ').append(token);
        if (!productPresent) line.append(" limit_rejected=false");
        String result = line.toString();
        try {
            Files.createDirectories(ROOT_RESULT.getParent() == null ? Path.of(".") : ROOT_RESULT.getParent());
            if (RUN_RESULT.getParent() != null) Files.createDirectories(RUN_RESULT.getParent());
            Files.writeString(ROOT_RESULT, result + "\n");
            Files.writeString(RUN_RESULT, result + "\n");
        } catch (Throwable ignored) {
            // stdout is the fallback signal.
        }
        System.out.println("[SETHOME_PROBE] " + result);
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
