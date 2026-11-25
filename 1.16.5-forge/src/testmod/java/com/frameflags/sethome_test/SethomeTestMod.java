package com.frameflags.sethome_test;

import com.frameflags.sethome.Sethome;
import com.frameflags.sethome.core.HomeRecord;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.vector.Vector2f;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Test-only probe. Drives the real Brigadier commands as a joined player and
 * writes exactly one canonical SETHOME_TEST_RESULT line.
 */
@Mod("sethome_test")
public class SethomeTestMod {
    private static final String ACTION_BAR_MARKER = "[ACTION_BAR] ";
    private static final double START_X = 133.5;
    private static final double START_Y = 70.0;
    private static final double START_Z = -244.5;
    private static final float START_YAW = 37.5f;
    private static final float START_PITCH = -12.25f;

    private MinecraftServer server;
    private ServerPlayerEntity player;
    private boolean finished;
    private boolean productChecked;
    private boolean failed;
    private int ticks;
    private int state;

    private String setToken = "?";
    private String teleportToken = "?";
    private String listToken = "?";
    private String deleteToken = "?";
    private String bareToken = "?";
    private String reservedToken = "?";
    private String limitToken = "?";
    private String countdownToken = "?";
    private String noSpamToken = "?";
    private String reason = "none";
    private String detail = "none";

    private long countdownDeadline = -1;
    private int countdownChatAtStart;
    private boolean countdownEarlyChecked;
    private long bareDeadline = -1;
    private int bareChatAtStart;
    private boolean bareEarlyChecked;
    private List<String> lastTickChats;
    private double awayX;
    private double awayY;
    private double awayZ;
    private float awayYaw;
    private float awayPitch;

    public SethomeTestMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onServerStarted(FMLServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished || server == null) return;

        // Negative control: if the product jar is absent, report a canonical FAIL
        // before touching any product class (this method stays Class.forName-based).
        if (!productChecked) {
            productChecked = true;
            try {
                Class.forName("com.frameflags.sethome.Sethome");
            } catch (Throwable missing) {
                finished = true;
                String line = "SETHOME_TEST_RESULT: FAIL reason=no-product detail=missing_product_jar"
                        + " product=false limit_rejected set=fail teleport=fail list=fail"
                        + " delete=fail bare=fail reserved=fail limit=fail countdown=skipped no-spam=fail";
                writeNegative(line);
                return;
            }
        }

        ticks++;

        checkNoSpam();
        if (failed) {
            writeResult();
            return;
        }

        if (player == null || !player.isAlive()) {
            List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                player = players.get(0);
            } else if (ticks >= 200) {
                fail("no-player", "no player joined after 200 ticks");
            }
            if (player == null) {
                if (failed) writeResult();
                return;
            }
        }

        try {
            step();
        } catch (RuntimeException e) {
            fail("exception", String.valueOf(e.getMessage()));
        }
        if (failed) writeResult();
    }

    private void step() {
        switch (state) {
            case 0:
                setPose(START_X, START_Y, START_Z, START_YAW, START_PITCH);
                int before = chatCount();
                dispatch("sethome alpha");
                HomeRecord alpha = Sethome.data().get(player.getUUID(), "alpha");
                if (chatCount() - before != 1 || alpha == null
                        || !close(alpha.x, START_X) || !close(alpha.y, START_Y)
                        || !close(alpha.z, START_Z)
                        || !close(alpha.yaw, START_YAW) || !close(alpha.pitch, START_PITCH)) {
                    fail("set", "sethome alpha did not store exact pose");
                    return;
                }
                setToken = "ok";
                state = 1;
                return;
            case 1:
                moveAway();
                int goBefore = chatCount();
                dispatch("home alpha");
                if (Sethome.config().countdownSeconds > 0) {
                    countdownChatAtStart = chatCount();
                    if (countdownChatAtStart - goBefore != 1) {
                        fail("countdown", "expected one chat confirmation at start");
                        return;
                    }
                    com.frameflags.sethome.core.Pending pending =
                            Sethome.queue().get(player.getUUID());
                    if (pending == null) {
                        fail("countdown", "teleport was not queued");
                        return;
                    }
                    countdownDeadline = pending.deadlineTick;
                    state = 2;
                } else {
                    if (chatCount() - goBefore != 1) {
                        fail("teleport", "expected one chat confirmation");
                        return;
                    }
                    if (!poseRestored(START_X, START_Y, START_Z, START_YAW, START_PITCH)) {
                        fail("teleport", "pose not restored");
                        return;
                    }
                    teleportToken = "ok";
                    countdownToken = "skipped";
                    state = 4;
                }
                return;
            case 2:
                // Countdown: assert no teleport before the deadline.
                // +1 tick tolerance: the probe listener may run before the
                // product's tick listener in the same server tick.
                if (Sethome.tick() <= countdownDeadline) {
                    // A real player falls under gravity while waiting; only arrival at
                    // the saved HOME pose counts as an early teleport.
                    if (poseRestored(START_X, START_Y, START_Z, START_YAW, START_PITCH)) {
                        fail("countdown", "teleported before deadline");
                        return;
                    }
                    countdownEarlyChecked = true;
                    return;
                }
                if (!poseRestored(START_X, START_Y, START_Z, START_YAW, START_PITCH)) {
                    fail("countdown", "pose not restored at deadline");
                    return;
                }
                if (chatCount() - countdownChatAtStart != 1) {
                    fail("countdown", "expected one chat confirmation at completion");
                    return;
                }
                if (!countdownEarlyChecked) {
                    fail("countdown", "no early-tick observation");
                    return;
                }
                teleportToken = "ok";
                countdownToken = "ok";
                state = 4;
                return;
            case 4:
                int listBefore = chatCount();
                dispatch("home list");
                if (chatCount() - listBefore != 1
                        || !lastChat().contains("alpha")) {
                    fail("list", "list did not emit exactly one message with alpha");
                    return;
                }
                listToken = "ok";
                state = 5;
                return;
            case 5:
                int delBefore = chatCount();
                dispatch("delhome alpha");
                if (chatCount() - delBefore != 1
                        || Sethome.data().get(player.getUUID(), "alpha") != null) {
                    fail("delete", "delhome did not remove alpha with one message");
                    return;
                }
                deleteToken = "ok";
                state = 6;
                return;
            case 6:
                int missBefore = chatCount();
                dispatch("home alpha");
                String miss = lastChat();
                if (chatCount() - missBefore != 1 || !miss.contains("alpha")) {
                    fail("missing", "missing home did not emit exactly one message");
                    return;
                }
                state = 7;
                return;
            case 7:
                setPose(START_X + 1, START_Y, START_Z + 1, START_YAW + 5, START_PITCH);
                int bareSetBefore = chatCount();
                dispatch("sethome");
                if (chatCount() - bareSetBefore != 1
                        || Sethome.data().get(player.getUUID(), "home") == null) {
                    fail("bare", "bare sethome did not use home with one message");
                    return;
                }
                state = 8;
                return;
            case 8:
                moveAway();
                int bareGoBefore = chatCount();
                dispatch("home");
                if (chatCount() - bareGoBefore != 1) {
                    fail("bare", "bare home sent wrong message count");
                    return;
                }
                if (Sethome.config().countdownSeconds > 0) {
                    com.frameflags.sethome.core.Pending pending =
                            Sethome.queue().get(player.getUUID());
                    if (pending == null) {
                        fail("bare", "bare home did not queue a teleport");
                        return;
                    }
                    bareDeadline = pending.deadlineTick;
                    bareChatAtStart = chatCount();
                    state = 80;
                } else {
                    if (!poseRestored(START_X + 1, START_Y, START_Z + 1,
                            START_YAW + 5, START_PITCH)) {
                        fail("bare", "bare home did not use home");
                        return;
                    }
                    state = 9;
                }
                return;
            case 80:
                if (Sethome.tick() <= bareDeadline) {
                    if (poseRestored(START_X + 1, START_Y, START_Z + 1, START_YAW + 5, START_PITCH)) {
                        fail("bare", "bare home teleported before deadline");
                        return;
                    }
                    bareEarlyChecked = true;
                    return;
                }
                if (!poseRestored(START_X + 1, START_Y, START_Z + 1,
                        START_YAW + 5, START_PITCH)) {
                    fail("bare", "bare home did not restore at deadline");
                    return;
                }
                if (chatCount() - bareChatAtStart != 1 || !bareEarlyChecked) {
                    fail("bare", "bare home countdown messages incorrect");
                    return;
                }
                state = 9;
                return;
            case 9:
                int bareDelBefore = chatCount();
                dispatch("delhome");
                if (chatCount() - bareDelBefore != 1
                        || Sethome.data().get(player.getUUID(), "home") != null) {
                    fail("bare", "bare delhome did not use home with one message");
                    return;
                }
                bareToken = "ok";
                state = 10;
                return;
            case 10:
                int reservedBefore = chatCount();
                dispatch("sethome list");
                if (chatCount() - reservedBefore != 1
                        || !lastChat().toLowerCase(java.util.Locale.ROOT).contains("reserved")
                        || Sethome.data().get(player.getUUID(), "list") != null) {
                    fail("reserved", "list name was not rejected with one message");
                    return;
                }
                reservedToken = "ok";
                state = 11;
                return;
            case 11:
                if (Sethome.config().maxHomes == 1) {
                    int firstBefore = chatCount();
                    dispatch("sethome one");
                    if (chatCount() - firstBefore != 1
                            || Sethome.data().get(player.getUUID(), "one") == null) {
                        fail("limit", "first home did not succeed with one message");
                        return;
                    }
                    int limitBefore = chatCount();
                    dispatch("sethome two");
                    String limitMsg = lastChat();
                    if (chatCount() - limitBefore != 1
                            || !limitMsg.toLowerCase(java.util.Locale.ROOT).contains("limit")
                            || Sethome.data().get(player.getUUID(), "two") != null) {
                        fail("limit", "second home was not rejected with one message");
                        return;
                    }
                    int cleanupBefore = chatCount();
                    dispatch("delhome one");
                    if (chatCount() - cleanupBefore != 1) {
                        fail("limit", "cleanup delete emitted wrong message count");
                        return;
                    }
                    limitToken = "ok";
                } else {
                    limitToken = "skipped";
                }
                state = 12;
                return;
            case 12:
                noSpamToken = "ok";
                writeResult();
                return;
            default:
                fail("state", "unknown state " + state);
        }
    }

    private void setPose(double x, double y, double z, float yaw, float pitch) {
        player.setPos(x, y, z);
        player.yRot = yaw;
        player.xRot = pitch;
    }

    private void moveAway() {
        awayX = START_X + 60;
        awayY = START_Y + 6;
        awayZ = START_Z - 60;
        awayYaw = 200f;
        awayPitch = 45f;
        setPose(awayX, awayY, awayZ, awayYaw, awayPitch);
    }

    private boolean atPose(double x, double y, double z, float yaw, float pitch) {
        return close(player.getX(), x) && close(player.getY(), y) && close(player.getZ(), z)
                && close(player.yRot, yaw) && close(player.xRot, pitch);
    }

    private boolean poseRestored(double x, double y, double z, float yaw, float pitch) {
        return atPose(x, y, z, yaw, pitch);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 0.01;
    }

    private void dispatch(String command) {
        CommandDispatcher<CommandSource> dispatcher = server.getCommands().getDispatcher();
        CommandSource source = new CommandSource(
                player,
                player.position(),
                new Vector2f(player.yRot, player.xRot),
                (ServerWorld) player.level,
                0, // no op required
                player.getName().getString(),
                new StringTextComponent(player.getName().getString()),
                server,
                player);
        try {
            dispatcher.execute(command, source);
        } catch (CommandSyntaxException e) {
            fail("dispatch", command + ":" + e.getMessage());
        }
    }

    /** Chat messages only; action-bar lines are marked by the adapter. */
    private static List<String> chatMessages() {
        List<String> all = Sethome.recentMessages;
        List<String> out = new ArrayList<>();
        synchronized (all) {
            for (String text : all) {
                if (!text.startsWith(ACTION_BAR_MARKER)) out.add(text);
            }
        }
        return out;
    }

    private static int chatCount() {
        return chatMessages().size();
    }

    private static String lastChat() {
        List<String> chats = chatMessages();
        return chats.isEmpty() ? "" : chats.get(chats.size() - 1);
    }

    private void checkNoSpam() {
        List<String> chats = chatMessages();
        if (lastTickChats != null && !lastTickChats.isEmpty()) {
            String previous = lastTickChats.get(lastTickChats.size() - 1);
            for (int i = lastTickChats.size(); i < chats.size(); i++) {
                if (previous.equals(chats.get(i))) {
                    fail("spam", "same chat line on consecutive ticks");
                    return;
                }
            }
        }
        lastTickChats = chats;
    }

    private void fail(String shortReason, String shortDetail) {
        if (failed || finished) return;
        failed = true;
        reason = shortReason;
        detail = shortDetail;
    }

    private String resultLine() {
        String tokens = " set=" + setToken + " teleport=" + teleportToken
                + " list=" + listToken + " delete=" + deleteToken
                + " bare=" + bareToken + " reserved=" + reservedToken
                + " limit=" + limitToken + " countdown=" + countdownToken
                + " no-spam=" + noSpamToken;
        if (!failed && "ok".equals(setToken) && "ok".equals(teleportToken)
                && "ok".equals(listToken) && "ok".equals(deleteToken)
                && "ok".equals(bareToken) && "ok".equals(reservedToken)
                && !"?".equals(limitToken) && !"?".equals(countdownToken)
                && "ok".equals(noSpamToken)) {
            return "SETHOME_TEST_RESULT: PASS" + tokens;
        }
        return "SETHOME_TEST_RESULT: FAIL reason=" + reason
                + " detail=" + sanitize(detail) + tokens;
    }

    private static String sanitize(String text) {
        String shortText = text == null ? "none" : text;
        if (shortText.length() > 60) shortText = shortText.substring(0, 60);
        return shortText.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Writes the negative-control line without touching product classes. */
    private void writeNegative(String line) {
        Path gameDir = server.getServerDirectory().toPath().toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Set<Path> dirs = new LinkedHashSet<>(Arrays.asList(gameDir, gameDir.getParent(), cwd, cwd.resolve("run")));
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        for (Path dir : dirs) {
            if (dir == null) continue;
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve("sethome-result.txt"), bytes);
            } catch (IOException ignored) {
            }
        }
        System.out.println("[SETHOME_PROBE] " + line);
    }

    private void writeResult() {
        if (finished) return;
        finished = true;
        String line = resultLine();
        Path gameDir = server.getServerDirectory().toPath().toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Set<Path> dirs = new LinkedHashSet<>(Arrays.asList(
                gameDir,
                gameDir.getParent(),
                cwd,
                cwd.resolve("run")));
        for (Path dir : dirs) {
            if (dir == null) continue;
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve("sethome-result.txt"),
                        (line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Try the next location.
            }
        }
        System.out.println("[SETHOME_PROBE] " + line);
    }
}
