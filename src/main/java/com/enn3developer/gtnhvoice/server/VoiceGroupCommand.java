package com.enn3developer.gtnhvoice.server;

import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.server.group.GlobalGroup;
import com.enn3developer.gtnhvoice.server.group.GroupManager;

/**
 * {@code /voicegroup <join|leave> global}: op-only membership toggle of the sender's own global-group
 * membership (local is implicit and not joinable/leavable; multi-group membership means joining global no
 * longer leaves anything). Server-authoritative like all membership changes - it goes straight through
 * {@link GroupManager#join}/{@link GroupManager#leave} (whose listener syncs the new top-priority label to the
 * HUD). Runs on the server thread; dedicated-server-safe, no client classes touched.
 */
public class VoiceGroupCommand extends CommandBase {

    private static final String JOIN = "join";
    private static final String LEAVE = "leave";

    @Override
    public String getCommandName() {
        return "voicegroup";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/voicegroup <" + JOIN + "|" + LEAVE + "> " + GlobalGroup.NAME;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, JOIN, LEAVE);
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, GlobalGroup.NAME);
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 2) throw new WrongUsageException(getCommandUsage(sender));
        boolean join = args[0].equals(JOIN);
        if (!join && !args[0].equals(LEAVE)) throw new WrongUsageException(getCommandUsage(sender));
        // Explicit whitelist: this command only ever toggles the built-in global group. Groups other mods
        // registerGroup()ed are reachable through byName(), but their membership is theirs to manage.
        if (!args[1].equals(GlobalGroup.NAME)) throw new WrongUsageException(getCommandUsage(sender));

        GroupManager groupManager = VoiceServerManager.getInstance()
            .getGroupManager();
        IGroup group = groupManager.byName(args[1]);
        if (group == null) throw new WrongUsageException(getCommandUsage(sender));

        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        UUID playerUuid = player.getGameProfile()
            .getId();
        if (join) {
            groupManager.join(playerUuid, group);
            player.addChatMessage(new ChatComponentText("Joined voice group " + group.getDisplayName()));
        } else {
            groupManager.leave(playerUuid, group);
            player.addChatMessage(new ChatComponentText("Left voice group " + group.getDisplayName()));
        }
    }
}
