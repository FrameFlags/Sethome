package com.frameflags.sethome.command;

import com.frameflags.sethome.Sethome;
import com.frameflags.sethome.core.HomeRecord;
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

/** {@code /sethome [name]} — bare form stores the home named "home". */
public final class CommandSethome extends CommandBase {
    @Override
    public String getName() {
        return "sethome";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/sethome [name]";
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
        if (!(sender instanceof EntityPlayerMP)) {
            CommandUtil.message(sender, "Only players can set a home.");
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        UUID uuid = player.getUniqueID();
        HomeRecord record = new HomeRecord(player.posX, player.posY, player.posZ,
                player.rotationYaw, player.rotationPitch, String.valueOf(player.dimension));
        HomeResult result = Sethome.data().set(uuid, name, record, Sethome.config().maxHomes);

        switch (result) {
            case OK:
                Sethome.saveHomes();
                CommandUtil.message(sender, "Set home '" + name + "' at " + record.format() + ".");
                break;
            case RESERVED_NAME:
                CommandUtil.message(sender, "'list' is a reserved name; choose another home name.");
                break;
            case INVALID_NAME:
                CommandUtil.message(sender, "Invalid home name.");
                break;
            case LIMIT_REACHED:
                CommandUtil.message(sender, "Home limit reached (max " + Sethome.config().maxHomes + ").");
                break;
            default:
                CommandUtil.message(sender, "Could not set home '" + name + "'.");
                break;
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
        return new ArrayList<String>();
    }
}
