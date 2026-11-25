package com.frameflags.sethome.command;

import com.frameflags.sethome.Sethome;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.TextComponentString;

/** Single place that sends a chat message and records it for the in-game probe. */
public final class CommandUtil {
    private CommandUtil() {
    }

    public static void message(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
        Sethome.recordMessage(text);
    }

    /** Action bars are recorded with a marker so the probe can ignore them in chat-spam checks. */
    public static void actionBar(net.minecraft.entity.player.EntityPlayerMP player, String text) {
        player.sendStatusMessage(new TextComponentString(text), true);
        Sethome.recordMessage(Sethome.ACTION_BAR_MARKER + text);
    }
}
