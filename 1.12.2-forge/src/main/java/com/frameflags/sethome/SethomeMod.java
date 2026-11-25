package com.frameflags.sethome;

import com.frameflags.sethome.command.CommandDelHome;
import com.frameflags.sethome.command.CommandHome;
import com.frameflags.sethome.command.CommandSethome;
import com.frameflags.sethome.command.CommandUtil;
import com.frameflags.sethome.core.HomeRecord;
import com.frameflags.sethome.core.Pending;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Server-side entry point: startup init, real command registration, and the countdown loop. */
@Mod(modid = SethomeMod.MODID, name = SethomeMod.NAME, version = SethomeMod.VERSION,
        acceptableRemoteVersions = "*")
public final class SethomeMod {
    public static final String MODID = "sethome";
    public static final String NAME = "Sethome";
    public static final String VERSION = "1.0.0";

    private static final Set<UUID> COUNTDOWNS = Collections.newSetFromMap(
            new ConcurrentHashMap<UUID, Boolean>());

    private MinecraftServer server;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Sethome.init(event.getModConfigurationDirectory().getParentFile().toPath());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        server = event.getServer();
        Sethome.init(server.getDataDirectory().toPath());
        event.registerServerCommand(new CommandSethome());
        event.registerServerCommand(new CommandHome());
        event.registerServerCommand(new CommandDelHome());
        COUNTDOWNS.clear();
    }

    /** Called by {@link CommandHome} when a countdown is queued. */
    public static void trackCountdown(UUID player) {
        if (player != null) COUNTDOWNS.add(player);
    }

    /** Restores the raw stored double pose (deliberately no +0.5 block-centre offset). */
    public static void teleport(EntityPlayerMP player, HomeRecord home) {
        if (player == null || home == null) return;
        int dimension = player.dimension;
        try {
            dimension = Integer.parseInt(home.dimension);
        } catch (RuntimeException ignored) {
            // Unparseable dimension keys keep the player in the current world.
        }
        EntityPlayerMP subject = player;
        if (dimension != player.dimension && player.getServer().getWorld(dimension) != null) {
            Entity moved = player.changeDimension(dimension);
            if (moved instanceof EntityPlayerMP) subject = (EntityPlayerMP) moved;
        }
        subject.setPositionAndRotation(home.x, home.y, home.z, home.yaw, home.pitch);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        List<Pending> due = Sethome.advanceTick();
        for (Pending pending : due) {
            COUNTDOWNS.remove(pending.player);
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(pending.player);
            if (player == null) continue;
            // Re-resolve at fire time: the home may have been deleted meanwhile.
            HomeRecord home = Sethome.data().get(pending.player, pending.homeName);
            if (home == null) {
                CommandUtil.message(player, "No home named '" + pending.homeName + "'.");
                continue;
            }
            teleport(player, home);
            CommandUtil.message(player, "Teleported to home '" + pending.homeName + "' at " + home.format() + ".");
        }

        Iterator<UUID> iterator = COUNTDOWNS.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            Pending pending = Sethome.queue().get(uuid);
            if (pending == null) {
                iterator.remove();
                continue;
            }
            long remaining = pending.deadlineTick - Sethome.tick();
            if (remaining <= 0) continue;
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(uuid);
            if (player == null) continue;
            // Action bar only: never a chat line per tick. Recorded with a marker so the
            // probe can exclude action bars from its repeated-chat-line check.
            if (remaining <= 3L || remaining % 10L == 0L) {
                CommandUtil.actionBar(player, "Teleport in " + ((remaining + 19L) / 20L) + "s");
            }
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityPlayerMP) {
            UUID uuid = event.getEntity().getUniqueID();
            Sethome.queue().cancel(uuid);
            COUNTDOWNS.remove(uuid);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            UUID uuid = event.player.getUniqueID();
            Sethome.queue().cancel(uuid);
            COUNTDOWNS.remove(uuid);
        }
    }
}
