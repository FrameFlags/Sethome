package com.frameflags.sethome;

import com.frameflags.sethome.core.HomeRecord;
import com.frameflags.sethome.core.Pending;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod(SethomeMod.MODID)
public final class SethomeMod {
    public static final String MODID = "sethome";

    private static MinecraftServer server;

    public SethomeMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onDeath);
        MinecraftForge.EVENT_BUS.addListener(this::onLogout);
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        Sethome.init(server.getServerDirectory().toPath());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        HomeCommands.register(event.getDispatcher());
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;

        List<Pending> due = Sethome.advanceTick();
        for (Pending pending : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player);
            if (player == null) continue;
            HomeRecord record = Sethome.data().get(pending.player, pending.homeName);
            if (record == null) {
                HomeCommands.chat(player, "Home '" + pending.homeName + "' no longer exists; teleport cancelled.");
                continue;
            }
            HomeCommands.teleport(player, record);
            HomeCommands.chat(player, "Teleported to home '" + pending.homeName + "'.");
        }

        // Countdown is action-bar only; the text changes every tick so it can
        // never look like repeated chat spam.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Pending pending = Sethome.queue().get(player.getUUID());
            if (pending == null) continue;
            long remaining = pending.deadlineTick - Sethome.tick();
            if (remaining <= 0) continue;
            String text = "Teleporting to '" + pending.homeName + "' in "
                    + String.format("%.1f", remaining / 20.0) + "s";
            HomeCommands.actionBar(player, text);
        }
    }

    private void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Sethome.queue().cancel(player.getUUID());
        }
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Sethome.queue().cancel(player.getUUID());
        }
    }
}
