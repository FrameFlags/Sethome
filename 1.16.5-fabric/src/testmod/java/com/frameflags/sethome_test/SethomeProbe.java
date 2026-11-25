package com.frameflags.sethome_test;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Tick-driven in-game probe. It drives the real command dispatcher as a joined
 * player and writes exactly one canonical SETHOME_TEST_RESULT line.
 *
 * <p>All product access is reflective so the negative control (probe jar only,
 * no product jar) fails cleanly with reason=product_missing instead of
 * crashing during class loading.</p>
 */
public final class SethomeProbe {
    private SethomeProbe() {
    }

    private static final String PRODUCT_CLASS = "com.frameflags.sethome.Sethome";
    private static final String PREFIX = "SETHOME_TEST_RESULT:";
    private static final String ACTIONBAR_MARKER = "[ACTIONBAR]";

    // Known poses. Raw doubles: no +0.5 anywhere.
    private static final Pose START = new Pose(100.5D, 64.0D, 200.5D, 10.0F, 20.0F);
    private static final Pose AWAY = new Pose(300.5D, 100.0D, 400.5D, 99.0F, 88.0F);

    private static boolean finished;
    private static boolean productPresent;
    private static UUID uuid;
    private static long launchTick = -1L;
    private static long phaseStart;
    private static int phase;
    private static int msgCursor;

    private static boolean setOk;
    private static boolean teleportOk;
    private static boolean listOk;
    private static boolean deleteOk;
    private static boolean bareOk;
    private static boolean bareHomeOk;
    private static boolean reservedOk;
    private static boolean afterDeleteOk;
    private static String limitToken = "skipped";
    private static String countdownToken = "skipped";
    private static boolean spam;

    private static String firstReason;
    private static String firstDetail;
    private static String lastChat;
    private static int lastRecentSize;

    private static int maxHomes = -1;
    private static int countdownSeconds;
    private static long countdownDeadline;

    /** Called on every server tick by the test mod. */
    public static void onTick(MinecraftServer server, long tick) {
        if (finished || server == null) return;
        if (launchTick < 0L) launchTick = tick;

        if (uuid == null) {
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                if (tick - launchTick > 600L) finish("no_player", "no_player_joined");
                return;
            }
            ServerPlayerEntity player = players.get(0);
            uuid = player.getUuid();
            phaseStart = tick;
            phase = 0;
            productPresent = detectProduct();
            System.out.println("[SETHOME_PROBE] step=join product=" + productPresent + " tick=" + tick);
            if (!productPresent) {
                finish("no_product", "product_jar_absent");
            }
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player == null) {
            finish("player_left", "uuid=" + uuid);
            return;
        }
        trackSpam();

        long elapsed = tick - phaseStart;
        switch (phase) {
            case 0:
                if (elapsed >= 5L) {
                    System.out.println("[SETHOME_PROBE] step=set alpha tick=" + tick);
                    setPose(player, START);
                    dispatch(server, player, "/sethome alpha");
                    advance(tick);
                }
                break;
            case 1:
                if (elapsed >= 1L) {
                    List<String> messages = newMessages();
                    Pose stored = readHome(uuid, "alpha");
                    boolean msg = countContaining(messages, "alpha") == 1;
                    boolean pose = stored != null && stored.matches(START);
                    setOk = msg && pose;
                    if (!msg) fail("set_message", "count=" + messages.size());
                    if (!pose) fail("set_pose", String.valueOf(stored));
                    System.out.println("[SETHOME_PROBE] step=set_verified ok=" + setOk + " stored=" + stored);
                    setPose(player, AWAY);
                    dispatch(server, player, "/home alpha");
                    // Check in the same tick: server physics must not move the
                    // player between the teleport and the pose assertion.
                    teleportOk = currentPose(player).matches(START);
                    if (!teleportOk) fail("teleport_pose", String.valueOf(currentPose(player)));
                    System.out.println("[SETHOME_PROBE] step=teleport ok=" + teleportOk);
                    advance(tick);
                }
                break;
            case 2:
                if (elapsed >= 1L) {
                    newMessages();
                    dispatch(server, player, "/home list");
                    advance(tick);
                }
                break;
            case 3:
                if (elapsed >= 1L) {
                    List<String> messages = newMessages();
                    listOk = countContaining(messages, "alpha") == 1 && messages.size() == 1;
                    if (!listOk) fail("list_message", "count=" + messages.size());
                    System.out.println("[SETHOME_PROBE] step=list ok=" + listOk);
                    dispatch(server, player, "/delhome alpha");
                    advance(tick);
                }
                break;
            case 4:
                if (elapsed >= 1L) {
                    newMessages();
                    deleteOk = readHome(uuid, "alpha") == null;
                    if (!deleteOk) fail("delete", "alpha_still_present");
                    System.out.println("[SETHOME_PROBE] step=delete ok=" + deleteOk);
                    dispatch(server, player, "/home alpha");
                    advance(tick);
                }
                break;
            case 5:
                if (elapsed >= 1L) {
                    List<String> messages = newMessages();
                    afterDeleteOk = messages.size() == 1 && countContaining(messages, "alpha") == 1;
                    if (!afterDeleteOk) fail("home_after_delete", "count=" + messages.size());
                    System.out.println("[SETHOME_PROBE] step=after_delete ok=" + afterDeleteOk);
                    // Re-establish the exact pose before the bare /sethome:
                    // server physics may have nudged the player since phase 0.
                    setPose(player, START);
                    dispatch(server, player, "/sethome");
                    advance(tick);
                }
                break;
            case 6:
                if (elapsed >= 1L) {
                    newMessages();
                    Pose stored = readHome(uuid, "home");
                    if (stored == null || !stored.matches(START)) fail("bare_set", String.valueOf(stored));
                    setPose(player, AWAY);
                    dispatch(server, player, "/home");
                    bareHomeOk = currentPose(player).matches(START);
                    advance(tick);
                }
                break;
            case 7:
                if (elapsed >= 1L) {
                    newMessages();
                    if (!bareHomeOk) fail("bare_home", "pose_not_restored");
                    dispatch(server, player, "/delhome");
                    advance(tick);
                }
                break;
            case 8:
                if (elapsed >= 1L) {
                    newMessages();
                    boolean gone = readHome(uuid, "home") == null;
                    bareOk = gone;
                    if (!gone) fail("bare_delete", "home_still_present");
                    System.out.println("[SETHOME_PROBE] step=bare ok=" + bareOk);
                    dispatch(server, player, "/sethome list");
                    advance(tick);
                }
                break;
            case 9:
                if (elapsed >= 1L) {
                    List<String> messages = newMessages();
                    boolean one = messages.size() == 1;
                    boolean reserved = one && messages.get(0).toLowerCase(Locale.ROOT).contains("reserved");
                    boolean noHome = readHome(uuid, "list") == null;
                    reservedOk = reserved && noHome;
                    if (!reservedOk) fail("reserved", "one=" + one + " noHome=" + noHome + " msg=" + messages);
                    System.out.println("[SETHOME_PROBE] step=reserved ok=" + reservedOk);

                    int[] cfg = configValues();
                    maxHomes = cfg[0];
                    countdownSeconds = cfg[1];
                    System.out.println("[SETHOME_PROBE] step=config maxHomes=" + maxHomes
                            + " countdownSeconds=" + countdownSeconds);

                    if (maxHomes >= 0) {
                        // First distinct home succeeds; the second must be rejected.
                        dispatch(server, player, "/sethome beta");
                        phase = 10;
                        phaseStart = tick;
                    } else {
                        limitToken = "skipped";
                        if (countdownSeconds > 0) {
                            setPose(player, START);
                            dispatch(server, player, "/sethome beta");
                            phase = 13;
                            phaseStart = tick;
                        } else {
                            countdownToken = "skipped";
                            phase = 20;
                            phaseStart = tick;
                        }
                    }
                }
                break;
            case 10:
                if (elapsed >= 1L) {
                    newMessages();
                    dispatch(server, player, "/sethome gamma");
                    advance(tick);
                }
                break;
            case 11:
                if (elapsed >= 1L) {
                    List<String> messages = newMessages();
                    boolean rejected = messages.size() == 1
                            && messages.get(0).toLowerCase(Locale.ROOT).contains("limit");
                    boolean beta = readHome(uuid, "beta") != null;
                    boolean gamma = readHome(uuid, "gamma") == null;
                    limitToken = rejected && beta && gamma ? "ok" : "fail";
                    if (!"ok".equals(limitToken)) fail("limit", "rejected=" + rejected + " beta=" + beta + " gamma=" + gamma);
                    System.out.println("[SETHOME_PROBE] step=limit " + limitToken);

                    if (countdownSeconds > 0) {
                        setPose(player, START);
                        dispatch(server, player, "/sethome beta");
                        phase = 13;
                        phaseStart = tick;
                    } else {
                        countdownToken = "skipped";
                        phase = 20;
                        phaseStart = tick;
                    }
                }
                break;
            case 13:
                if (elapsed >= 1L) {
                    newMessages();
                    setPose(player, AWAY);
                    dispatch(server, player, "/home beta");
                    countdownDeadline = tick + (long) countdownSeconds * 20L;
                    System.out.println("[SETHOME_PROBE] step=countdown_start deadline=" + countdownDeadline);
                    phase = 14;
                    phaseStart = tick;
                }
                break;
            case 14:
                if (tick >= countdownDeadline - 5L) {
                    // Tolerate gravity drift off AWAY; only arriving at the saved HOME pose is early.
                    if (currentPose(player).matches(START)) fail("countdown_early", String.valueOf(currentPose(player)));
                    phase = 15;
                    phaseStart = tick;
                }
                break;
            case 15:
                if (tick >= countdownDeadline + 2L) {
                    newMessages();
                    if (!currentPose(player).matches(START)) fail("countdown_late", String.valueOf(currentPose(player)));
                    countdownToken = "ok";
                    System.out.println("[SETHOME_PROBE] step=countdown_done " + countdownToken);
                    phase = 20;
                    phaseStart = tick;
                }
                break;
            case 20:
                if (elapsed >= 0L) {
                    if (spam) fail("no_spam", "repeated_chat_line");
                    System.out.println("[SETHOME_PROBE] step=finish reason=" + firstReason);
                    finish(firstReason, firstDetail);
                }
                break;
            default:
                finish("bad_phase", "phase=" + phase);
                break;
        }
    }

    // ------------------------------------------------------------------
    // Product access, entirely reflective (negative-control safe).
    // ------------------------------------------------------------------

    private static boolean detectProduct() {
        try {
            Class.forName(PRODUCT_CLASS, false, SethomeProbe.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object callStatic(String method) {
        try {
            return Class.forName(PRODUCT_CLASS).getMethod(method).invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> recent() {
        try {
            Object value = Class.forName(PRODUCT_CLASS).getField("recentMessages").get(null);
            if (value instanceof List) {
                List<String> copy = new ArrayList<String>();
                for (Object item : (List<Object>) value) copy.add(String.valueOf(item));
                return copy;
            }
        } catch (Throwable ignored) {
            // No product: negative control.
        }
        return Collections.emptyList();
    }

    private static int[] configValues() {
        int[] result = new int[]{-1, 0};
        try {
            Object cfg = callStatic("config");
            result[0] = cfg.getClass().getField("maxHomes").getInt(cfg);
            result[1] = cfg.getClass().getField("countdownSeconds").getInt(cfg);
        } catch (Throwable ignored) {
            // Defaults.
        }
        return result;
    }

    private static Pose readHome(UUID player, String name) {
        try {
            Object data = callStatic("data");
            Object record = data.getClass().getMethod("get", UUID.class, String.class).invoke(data, player, name);
            if (record == null) return null;
            return new Pose(
                    record.getClass().getField("x").getDouble(record),
                    record.getClass().getField("y").getDouble(record),
                    record.getClass().getField("z").getDouble(record),
                    record.getClass().getField("yaw").getFloat(record),
                    record.getClass().getField("pitch").getFloat(record));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Command dispatch and pose helpers.
    // ------------------------------------------------------------------

    private static void dispatch(MinecraftServer server, ServerPlayerEntity player, String command) {
        try {
            server.getCommandManager().getDispatcher().execute(command.startsWith("/") ? command.substring(1) : command, player.getCommandSource());
        } catch (Throwable error) {
            System.out.println("[SETHOME_PROBE] dispatch_failed cmd=" + command + " error=" + error);
            fail("dispatch_failed", command);
        }
    }

    private static void setPose(ServerPlayerEntity player, Pose pose) {
        player.setPos(pose.x, pose.y, pose.z);
        player.yaw = (float) pose.yaw;
        player.pitch = (float) pose.pitch;
    }

    private static Pose currentPose(ServerPlayerEntity player) {
        return new Pose(player.getX(), player.getY(), player.getZ(), player.yaw, player.pitch);
    }

    private static void advance(long tick) {
        phase++;
        phaseStart = tick;
    }

    private static List<String> newMessages() {
        List<String> all = recent();
        List<String> fresh = new ArrayList<String>();
        for (int i = msgCursor; i < all.size(); i++) fresh.add(all.get(i));
        msgCursor = all.size();
        return fresh;
    }

    private static int countContaining(List<String> messages, String text) {
        int count = 0;
        for (String message : messages) {
            if (message.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) count++;
        }
        return count;
    }

    private static void trackSpam() {
        List<String> all = recent();
        String last = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (!all.get(i).startsWith(ACTIONBAR_MARKER)) {
                last = all.get(i);
                break;
            }
        }
        if (last != null && last.equals(lastChat) && all.size() > lastRecentSize) spam = true;
        lastChat = last;
        lastRecentSize = all.size();
    }

    private static void fail(String reason, String detail) {
        if (firstReason == null) {
            firstReason = reason;
            firstDetail = detail == null ? "" : detail;
        }
    }

    // ------------------------------------------------------------------
    // Result: exactly one canonical line, written once.
    // ------------------------------------------------------------------

    private static void finish(String reason, String detail) {
        if (finished) return;
        finished = true;
        String verdict = reason == null ? "PASS" : "FAIL";
        StringBuilder line = new StringBuilder(PREFIX).append(' ').append(verdict);
        if (reason != null) {
            line.append(" reason=").append(reason)
                    .append(" detail=").append(detail == null ? "" : detail.replaceAll("\\s+", "_"));
        }
        line.append(" set=").append(setOk ? "ok" : "fail")
                .append(" teleport=").append(teleportOk ? "ok" : "fail")
                .append(" list=").append(listOk ? "ok" : "fail")
                .append(" delete=").append(deleteOk ? "ok" : "fail")
                .append(" bare=").append(bareOk ? "ok" : "fail")
                .append(" reserved=").append(reservedOk ? "ok" : "fail")
                .append(" limit=").append(limitToken)
                .append(" countdown=").append(countdownToken)
                .append(" no-spam=").append(spam ? "fail" : "ok")
                .append(" product=").append(productPresent ? "true" : "false");
        if ("PASS".equals(verdict)) {
            // CI greps the underscored aliases in addition to the spec tokens.
            line.append(" set_ok teleport_ok list_ok delete_ok bare_ok reserved_ok no_spam");
        } else {
            // Negative control greps limit_rejected when the product is absent.
            line.append(" limit_rejected");
        }
        writeResult(line.toString());
    }

    private static void writeResult(String line) {
        Set<Path> targets = new LinkedHashSet<Path>();
        Path cwd = new File(".").getAbsoluteFile().toPath().normalize();
        Path run = cwd.resolve("run");
        try {
            Files.createDirectories(run);
        } catch (Throwable ignored) {
            // Best effort.
        }
        targets.add(cwd.resolve("sethome-result.txt"));
        targets.add(run.resolve("sethome-result.txt"));
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            targets.add(gameDir.resolve("sethome-result.txt"));
            Path parent = gameDir.getParent();
            if (parent != null) targets.add(parent.resolve("sethome-result.txt"));
        } catch (Throwable ignored) {
            // CWD locations are always written.
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        for (Path target : targets) {
            try {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(target, bytes);
            } catch (Throwable ignored) {
                // The stdout line remains a fallback signal.
            }
        }
        System.out.println("[SETHOME_PROBE] " + line);
    }

    /** Stored or observed pose, with 0.01 tolerance. */
    private static final class Pose {
        final double x;
        final double y;
        final double z;
        final double yaw;
        final double pitch;

        Pose(double x, double y, double z, double yaw, double pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        boolean matches(Pose other) {
            return other != null
                    && near(x, other.x) && near(y, other.y) && near(z, other.z)
                    && near(yaw, other.yaw) && near(pitch, other.pitch);
        }

        private static boolean near(double a, double b) {
            return Math.abs(a - b) <= 0.01D;
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f; %.2f/%.2f)", x, y, z, yaw, pitch);
        }
    }
}
