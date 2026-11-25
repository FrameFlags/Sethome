package com.frameflags.sethome_test;

import com.frameflags.sethome.Sethome;
import com.frameflags.sethome.core.HomeRecord;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Tick-driven state machine. Every action is dispatched as a real Brigadier
 * command through the server's dispatcher as the joined player; the product's
 * static API is only read for observations.
 */
public final class HomeProbe {
    private static final String ACTION_BAR = "[ACTIONBAR] ";
    private static final double TOLERANCE = 0.01;

    private final MinecraftServer server;
    private int localTick;
    private int stage;
    private int nextStep = 4;
    private boolean done;

    private int messageIndex;
    private String lastChatMessage;
    private boolean spamOk = true;
    private String detail = "";

    private ServerPlayer player;
    private CommandSourceStack source;
    private UUID uuid;
    private double startX, startY, startZ, movedX, movedY, movedZ;
    private float startYaw, startPitch;
    private long countdownDeadline = -1;
    private boolean preDeadlineSeen;
    private boolean countdownTested;

    private String set = "fail";
    private String teleport = "fail";
    private String list = "fail";
    private String delete = "fail";
    private String bare = "fail";
    private String reserved = "fail";
    private String limit = "skipped";
    private String countdown = "skipped";

    HomeProbe(MinecraftServer server) {
        this.server = server;
    }

    boolean isDone() {
        return done;
    }

    void onTick() throws CommandSyntaxException {
        if (done) return;
        localTick++;

        // No-spam: index into the unfiltered list, then compare each newly
        // added chat message against the previous chat message. Action-bar
        // ticks are marked by the adapter and deliberately excluded.
        List<String> fresh = chatSince(messageIndex);
        messageIndex = Sethome.recentMessages.size();
        for (String message : fresh) {
            if (message.equals(lastChatMessage)) {
                spamOk = false;
                fail("spam", "repeated_chat_message");
                return;
            }
            lastChatMessage = message;
        }

        if (stage == 0) {
            if (player == null) {
                List<ServerPlayer> players = server.getPlayerList().getPlayers();
                if (players.isEmpty()) {
                    if (localTick > 600) fail("no-player", "no_joined_player");
                    return;
                }
                player = players.get(0);
                // 1.19.2 has no ServerPlayer.createCommandSourceStack(); build
                // the player's command source explicitly (permission 4, owner=self).
                source = new CommandSourceStack(player, player.position(),
                        new Vec2(player.getYRot(), player.getXRot()), player.getLevel(), 4,
                        player.getGameProfile().getName(), player.getDisplayName(), server, player);
                uuid = player.getUUID();
                // Flying with zero velocity pins the pose so gravity cannot
                // perturb the exact position assertions.
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.setDeltaMovement(Vec3.ZERO);
                player.setYRot(42.0f);
                player.setXRot(-12.0f);
                startX = player.getX();
                startY = player.getY();
                startZ = player.getZ();
                startYaw = player.getYRot();
                startPitch = player.getXRot();
                stage = 1;
                nextStep = localTick + 3;
                return;
            }
        }

        if (localTick < nextStep) return;
        switch (stage) {
            case 1 -> doSetAlpha();
            case 2 -> doHomeAlpha();
            case 3 -> awaitCountdown();
            case 4 -> doList();
            case 5 -> doDelete();
            case 6 -> doHomeAfterDelete();
            case 7 -> doBareSet();
            case 8 -> doBareHome();
            case 9 -> doBareDelete();
            case 10 -> doReserved();
            case 11 -> doLimit();
            case 12 -> finish();
            default -> fail("stage", "unknown_stage_" + stage);
        }
    }

    private void doSetAlpha() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/sethome alpha");
        HomeRecord record = Sethome.data().get(uuid, "alpha");
        if (!matches(record, startX, startY, startZ, startYaw, startPitch)) {
            detail = "stored_pose_mismatch";
            fail("set", detail);
            return;
        }
        if (record.dimension == null || record.dimension.isEmpty()) {
            fail("set", "missing_dimension");
            return;
        }
        if (!oneChat(from, "alpha", "set")) {
            fail("set", detail);
            return;
        }
        set = "ok";
        stage = 2;
        nextStep = localTick + 2;
    }

    private void doHomeAlpha() throws CommandSyntaxException {
        movedX = startX + 6.0;
        movedY = startY + 4.0;
        movedZ = startZ + 6.0;
        player.setPos(movedX, movedY, movedZ);
        player.setYRot(startYaw + 90.0f);
        player.setXRot(10.0f);
        player.setDeltaMovement(Vec3.ZERO);

        int from = Sethome.recentMessages.size();
        dispatch("/home alpha");
        String confirmToken = Sethome.config().countdownSeconds > 0 ? "teleporting" : "teleported";
        if (!oneChat(from, "alpha", confirmToken)) {
            fail("teleport", detail);
            return;
        }
        if (Sethome.config().countdownSeconds > 0) {
            if (Sethome.queue().get(uuid) == null) {
                fail("countdown", "not_scheduled");
                return;
            }
            countdownDeadline = Sethome.queue().get(uuid).deadlineTick;
            preDeadlineSeen = false;
            stage = 3;
            nextStep = localTick + 1;
            return;
        }
        if (!playerPoseMatches(startX, startY, startZ, startYaw, startPitch)) {
            fail("teleport", "pose_not_restored");
            return;
        }
        teleport = "ok";
        stage = 4;
        nextStep = localTick + 2;
    }

    private void awaitCountdown() {
        long now = Sethome.tick();
        if (now < countdownDeadline) {
            // Gravity can move a real player off the exact moved pose; only arrival
            // at the saved HOME pose before the deadline is an early teleport.
            if (playerPoseMatches(startX, startY, startZ, startYaw, startPitch)) {
                fail("countdown", "early_teleport");
                return;
            }
            // Prove the delay was actually observed before the deadline.
            preDeadlineSeen = true;
            return;
        }
        if (now > countdownDeadline + 1) {
            fail("countdown", "late_teleport");
            return;
        }
        // The product's tick listener may run just after this one, so allow a
        // single tick of slack for the teleport itself.
        if (!playerPoseMatches(startX, startY, startZ, startYaw, startPitch)) {
            if (now == countdownDeadline + 1) {
                fail("countdown", "pose_not_restored");
            }
            return;
        }
        if (!preDeadlineSeen) {
            fail("countdown", "no_pre_deadline_observation");
            return;
        }
        countdown = "ok";
        countdownTested = true;
        teleport = "ok";
        stage = 4;
        nextStep = localTick + 2;
    }

    private void doList() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/home list");
        if (!oneChat(from, "alpha", "homes:")) {
            fail("list", detail);
            return;
        }
        list = "ok";
        stage = 5;
        nextStep = localTick + 2;
    }

    private void doDelete() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/delhome alpha");
        if (Sethome.data().get(uuid, "alpha") != null) {
            fail("delete", "home_still_present");
            return;
        }
        if (!oneChat(from, "alpha", "deleted")) {
            fail("delete", detail);
            return;
        }
        delete = "ok";
        stage = 6;
        nextStep = localTick + 2;
    }

    private void doHomeAfterDelete() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/home alpha");
        if (!oneChat(from, "alpha", "no home named")) {
            fail("delete", detail);
            return;
        }
        stage = 7;
        nextStep = localTick + 2;
    }

    private void doBareSet() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/sethome");
        HomeRecord record = Sethome.data().get(uuid, "home");
        if (!matches(record, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot())) {
            fail("bare", "bare_sethome_pose_mismatch");
            return;
        }
        if (!oneChat(from, "home", "set")) {
            fail("bare", detail);
            return;
        }
        stage = 8;
        nextStep = localTick + 2;
    }

    private void doBareHome() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/home");
        if (!oneChat(from, "home", "teleported")) {
            fail("bare", detail);
            return;
        }
        stage = 9;
        nextStep = localTick + 2;
    }

    private void doBareDelete() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/delhome");
        if (Sethome.data().get(uuid, "home") != null) {
            fail("bare", "bare_delhome_failed");
            return;
        }
        if (!oneChat(from, "home", "deleted")) {
            fail("bare", detail);
            return;
        }
        bare = "ok";
        stage = 10;
        nextStep = localTick + 2;
    }

    private void doReserved() throws CommandSyntaxException {
        int from = Sethome.recentMessages.size();
        dispatch("/sethome list");
        if (Sethome.data().get(uuid, "list") != null) {
            fail("reserved", "list_home_was_created");
            return;
        }
        if (!oneChat(from, "reserved")) {
            fail("reserved", detail);
            return;
        }
        reserved = "ok";
        stage = 11;
        nextStep = localTick + 2;
    }

    private void doLimit() throws CommandSyntaxException {
        if (Sethome.config().maxHomes == 1) {
            dispatch("/sethome limitfirst");
            if (Sethome.data().get(uuid, "limitfirst") == null) {
                fail("limit", "first_home_rejected");
                return;
            }
            int from = Sethome.recentMessages.size();
            dispatch("/sethome limitsecond");
            if (Sethome.data().get(uuid, "limitsecond") != null) {
                fail("limit", "second_home_created");
                return;
            }
            if (!oneChat(from, "limit")) {
                fail("limit", detail);
                return;
            }
            limit = "ok";
        } else {
            limit = "skipped";
        }
        stage = 12;
        nextStep = localTick + 2;
    }

    private void finish() {
        if (Sethome.config().countdownSeconds > 0 && !countdownTested) {
            countdown = "skipped";
        }
        if (!spamOk) {
            fail("spam", "repeated_chat_message");
            return;
        }
        done = true;
        String canonical = "SETHOME_TEST_RESULT: PASS set=ok teleport=ok list=ok delete=ok bare=ok "
                + "reserved=ok limit=" + limit + " countdown=" + countdown + " no-spam=ok";
        String diagnostics = "product=true set_ok teleport_ok list_ok delete_ok bare_ok reserved_ok "
                + (limit.equals("ok") ? "limit_ok" : "limit_skipped") + " "
                + (countdown.equals("ok") ? "countdown_ok" : "countdown_skipped") + " no_spam";
        ProbeResult.write(server, canonical, diagnostics);
    }

    void fail(String reason, String why) {
        if (done) return;
        done = true;
        String shortWhy = why == null ? "unknown" : why.replaceAll("\\s+", "_").replace(':', '_');
        String canonical = "SETHOME_TEST_RESULT: FAIL reason=" + reason + " detail=" + shortWhy
                + " set=" + set + " teleport=" + teleport + " list=" + list + " delete=" + delete
                + " bare=" + bare + " reserved=" + reserved + " limit=" + limit
                + " countdown=" + countdown + " no-spam=" + (spamOk ? "ok" : "fail");
        String diagnostics = "product=true "
                + (set.equals("ok") ? "set_ok" : "set_fail") + " "
                + (teleport.equals("ok") ? "teleport_ok" : "teleport_fail") + " "
                + (list.equals("ok") ? "list_ok" : "list_fail") + " "
                + (delete.equals("ok") ? "delete_ok" : "delete_fail") + " "
                + (bare.equals("ok") ? "bare_ok" : "bare_fail") + " "
                + (reserved.equals("ok") ? "reserved_ok" : "reserved_fail") + " "
                + (spamOk ? "no_spam" : "spam_fail");
        ProbeResult.write(server, canonical, diagnostics);
    }

    private void dispatch(String command) throws CommandSyntaxException {
        server.getCommands().getDispatcher().execute(command, source);
    }

    private List<String> chatSince(int index) {
        List<String> out = new ArrayList<>();
        List<String> messages = Sethome.recentMessages;
        synchronized (messages) {
            for (int i = Math.max(0, index); i < messages.size(); i++) {
                String message = messages.get(i);
                if (!message.startsWith(ACTION_BAR)) out.add(message);
            }
        }
        return out;
    }

    private boolean oneChat(int from, String... tokens) {
        List<String> messages = chatSince(from);
        if (messages.size() != 1) {
            detail = "expected_one_message_got_" + messages.size() + "_for_" + String.join(",", tokens);
            return false;
        }
        String text = messages.get(0).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!text.contains(token.toLowerCase(Locale.ROOT))) {
                detail = "message_missing_token_" + token;
                return false;
            }
        }
        return true;
    }

    private boolean playerPoseMatches(double x, double y, double z, float yaw, float pitch) {
        return close(player.getX(), x) && close(player.getY(), y) && close(player.getZ(), z)
                && close(player.getYRot(), yaw) && close(player.getXRot(), pitch);
    }

    private static boolean matches(HomeRecord record, double x, double y, double z, float yaw, float pitch) {
        return record != null && close(record.x, x) && close(record.y, y) && close(record.z, z)
                && close(record.yaw, yaw) && close(record.pitch, pitch);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < TOLERANCE;
    }
}
