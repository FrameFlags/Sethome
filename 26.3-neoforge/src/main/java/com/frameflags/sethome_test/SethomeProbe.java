package com.frameflags.sethome_test;

import java.lang.reflect.Field;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

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

    private static Pose alphaPose;
    private static Pose farPose;
    private static boolean bareSetOk;
    private static boolean earlyTeleport;
    private static boolean countdownScheduleOk;
    private static boolean chatSeen;
    private static int alphaDeadline;
    private static int alphaChatBaseline;
    private static int bareDeadline;

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
        player.setNoGravity(true);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setDeltaMovement(Vec3.ZERO);
        clearChat();
    }

    public static void onTick(MinecraftServer server, int tick) {
        if (finished || !productPresent || target == null || server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(target);
        if (player == null) return;
        int elapsed = tick - startTick;
        if (elapsed < 40) return;
        if (elapsed > 1800) {
            check(false, "timeout");
            finish();
            return;
        }
        if (phase == 0) {
            begin(server, player, tick);
        } else if (phase == 1) {
            waitAlpha(server, player, tick);
        } else if (phase == 2) {
            waitBare(player, tick);
        }
    }

    private static void begin(MinecraftServer server, ServerPlayer player, int tick) {
        player.teleportTo((ServerLevel) player.level(), 100.5D, 64.5D, 100.5D, Set.of(), 90.0F, -10.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        alphaPose = pose(player);

        clearChat();
        int rc = dispatch(server, player, "sethome alpha");
        Pose stored = storedPose("alpha");
        check(rc > 0 && stored != null && matches(stored, alphaPose) && chatCount() == 1, "set_ok");

        rc = dispatch(server, player, "sethome");
        bareSetOk = rc > 0 && hasHome("home");

        clearChat();
        rc = dispatch(server, player, "sethome list");
        System.out.println("[SETHOME_PROBE] reserved diag rc=" + rc + " hasList=" + hasHome("list") + " chats=" + chatCount());
        check(rc == 0 && !hasHome("list") && chatCount() == 1, "reserved_ok");

        clearChat();
        rc = dispatch(server, player, "home list");
        String listLog = String.join("|", chatLog());
        chatSeen = chatCount() > 0;
        check(rc > 0 && chatCount() == 1 && listLog.contains("alpha"), "list_ok");

        clearChat();
        player.teleportTo((ServerLevel) player.level(), 1000.5D, 120.5D, 1000.5D, Set.of(), 0.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        farPose = pose(player);
        earlyTeleport = false;
        countdownScheduleOk = countdownSeconds <= 0;

        rc = dispatch(server, player, "home alpha");
        if (countdownSeconds > 0) {
            Long deadline = queueDeadline();
            alphaDeadline = deadline == null ? tick + countdownSeconds * 20 : deadline.intValue();
            countdownScheduleOk = countdownScheduleOk && alphaDeadline >= tick + countdownSeconds * 20 - 2;
        } else {
            alphaDeadline = tick + 2;
        }
        if (rc <= 0) check(false, "teleport_ok");
        alphaChatBaseline = chatCount();
        phase = 1;
    }

    private static void waitAlpha(MinecraftServer server, ServerPlayer player, int tick) {
        // With instant teleports the player is already home when this phase starts;
        // only a scheduled countdown can produce a genuine "early teleport".
        if (countdownSeconds > 0 && tick < alphaDeadline) {
            if (!matches(pose(player), farPose)) earlyTeleport = true;
            return;
        }
        boolean arrived = matches(pose(player), alphaPose);
        check(arrived, "teleport_ok");
        check(countdownScheduleOk && !earlyTeleport && chatCount() - alphaChatBaseline <= 2, "countdown_ok");

        clearChat();
        int rc = dispatch(server, player, "delhome alpha");
        boolean deleted = rc > 0 && !hasHome("alpha") && chatCount() == 1;
        clearChat();
        rc = dispatch(server, player, "home alpha");
        boolean oneError = rc == 0 && chatCount() == 1;
        check(deleted && oneError, "delete_ok");

        player.teleportTo((ServerLevel) player.level(), 1000.5D, 120.5D, 1000.5D, Set.of(), 0.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        clearChat();
        rc = dispatch(server, player, "home");
        if (countdownSeconds > 0 && rc > 0) {
            Long deadline = queueDeadline();
            bareDeadline = deadline == null ? tick + countdownSeconds * 20 : deadline.intValue();
        } else {
            bareDeadline = tick + 2;
        }
        phase = 2;
    }

    private static void waitBare(ServerPlayer player, int tick) {
        if (tick < bareDeadline) return;
        System.out.println("[SETHOME_PROBE] bare diag set=" + bareSetOk + " now=" + pose(player).x + "," + pose(player).y + "," + pose(player).z + "," + pose(player).yaw + "," + pose(player).pitch + " alpha=" + alphaPose.x + "," + alphaPose.y + "," + alphaPose.z + "," + alphaPose.yaw + "," + alphaPose.pitch);
        check(bareSetOk && matches(pose(player), alphaPose), "bare_ok");

        if (maxHomes == 1) {
            clearChat();
            int rc = dispatch(player.level().getServer(), player, "sethome beta");
            boolean rejected = rc == 0 && !hasHome("beta") && chatCount() == 1;
            check(rejected, "limit_rejected");
        } else {
            int rc = dispatch(player.level().getServer(), player, "sethome beta");
            check(rc > 0 && hasHome("beta"), "limit_ok");
            dispatch(player.level().getServer(), player, "delhome beta");
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
        call("clearChatLog", new Class<?>[] { UUID.class }, target);
    }

    private static Long queueDeadline() {
        Object queue = call("queue", new Class<?>[0]);
        if (queue == null) return null;
        try {
            Object pending = queue.getClass().getMethod("get", UUID.class).invoke(queue, target);
            if (pending == null) return null;
            Field field = pending.getClass().getField("deadlineTick");
            return field.getLong(pending);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean noRepeatedChat() {
        List<String> log = chatLog();
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
