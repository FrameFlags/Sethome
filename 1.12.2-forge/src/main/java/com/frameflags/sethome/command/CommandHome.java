package com.frameflags.sethome.command;

import com.frameflags.sethome.Sethome;
import com.frameflags.sethome.SethomeMod;
import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.HomeRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

/** {@code /home [name]} and {@code /home list}. Bare form uses "home". */
public final class CommandHome extends CommandBase {
    @Override
    public String getName() {
        return "home";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/home [name|list]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            CommandUtil.message(sender, "Only players can use a home.");
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        UUID uuid = player.getUniqueID();
        String name = args.length > 0 ? args[0] : "home";

        if (HomeData.normalize(name).equals("list")) {
            List<String> homes = Sethome.data().list(uuid);
            StringBuilder sb = new StringBuilder(homes.isEmpty() ? "You have no homes." : "Homes:");
            for (String home : homes) {
                HomeRecord record = Sethome.data().get(uuid, home);
                sb.append('\n').append(home).append(" (")
                        .append(record == null ? "?" : record.format()).append(')');
            }
            CommandUtil.message(sender, sb.toString());
            return;
        }

        String normalized = HomeData.normalize(name);
        if (Sethome.data().get(uuid, normalized) == null) {
            CommandUtil.message(sender, "No home named '" + normalized + "'.");
            return;
        }

        int seconds = Sethome.config().countdownSeconds;
        if (seconds > 0) {
            long deadline = Sethome.tick() + (long) seconds * 20L;
            Sethome.queue().schedule(uuid, normalized, deadline);
            SethomeMod.trackCountdown(uuid);
            CommandUtil.message(sender, "Teleporting to home '" + normalized + "' in " + seconds + "s.");
        } else {
            HomeRecord record = Sethome.data().get(uuid, normalized);
            if (record == null) {
                CommandUtil.message(sender, "No home named '" + normalized + "'.");
                return;
            }
            SethomeMod.teleport(player, record);
            CommandUtil.message(sender, "Teleported to home '" + normalized + "' at " + record.format() + ".");
        }
    }

    /** Home completion offers only this player's own home names. */
    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
        List<String> matches = new ArrayList<String>();
        if (!(sender instanceof EntityPlayerMP) || args.length == 0) return matches;
        String prefix = HomeData.normalize(args[args.length - 1]);
        for (String home : Sethome.data().list(((EntityPlayerMP) sender).getUniqueID())) {
            if (home.startsWith(prefix)) matches.add(home);
        }
        return matches;
    }
}
