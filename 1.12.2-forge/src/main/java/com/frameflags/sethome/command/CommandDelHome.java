package com.frameflags.sethome.command;

import com.frameflags.sethome.Sethome;
import com.frameflags.sethome.core.HomeData;
import com.frameflags.sethome.core.HomeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

/** {@code /delhome [name]} — bare form deletes the home named "home". */
public final class CommandDelHome extends CommandBase {
    @Override
    public String getName() {
        return "delhome";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/delhome [name]";
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
        String name = args.length > 0 ? args[0] : "home";
        String normalized = HomeData.normalize(name);
        if (!(sender instanceof EntityPlayerMP)) {
            CommandUtil.message(sender, "Only players can delete a home.");
            return;
        }
        UUID uuid = ((EntityPlayerMP) sender).getUniqueID();
        HomeResult result = Sethome.data().remove(uuid, normalized);
        if (result == HomeResult.OK) {
            Sethome.saveHomes();
            CommandUtil.message(sender, "Deleted home '" + normalized + "'.");
        } else {
            CommandUtil.message(sender, "No home named '" + normalized + "'.");
        }
    }

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
