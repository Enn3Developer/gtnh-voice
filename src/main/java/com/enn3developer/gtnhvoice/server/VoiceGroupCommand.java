package com.enn3developer.gtnhvoice.server;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.server.group.GlobalGroup;
import com.enn3developer.gtnhvoice.server.group.GroupManager;
import com.enn3developer.gtnhvoice.server.group.LocalGroup;

/**
 * {@code /voicegroup <local|global>}: op-only switch of the sender's own voice group. Server-authoritative like
 * all membership changes - this command is the only in-game way to move a player between the built-in groups,
 * and it goes straight through {@link GroupManager#assign} (whose listener syncs the new label to the HUD).
 * Runs on the server thread; dedicated-server-safe, no client classes touched.
 */
public class VoiceGroupCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "voicegroup";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/voicegroup <" + LocalGroup.NAME + "|" + GlobalGroup.NAME + ">";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return getListOfStringsMatchingLastWord(args, LocalGroup.NAME, GlobalGroup.NAME);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 1) throw new WrongUsageException(getCommandUsage(sender));
        // Explicit whitelist: this command only ever moves players between the two built-ins. Groups other
        // mods registerGroup()ed are reachable through byName(), but their membership is theirs to manage.
        if (!args[0].equals(LocalGroup.NAME) && !args[0].equals(GlobalGroup.NAME))
            throw new WrongUsageException(getCommandUsage(sender));

        GroupManager groupManager = VoiceServerManager.getInstance()
            .getGroupManager();
        IGroup group = groupManager.byName(args[0]);
        if (group == null) throw new WrongUsageException(getCommandUsage(sender));

        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        groupManager.assign(
            player.getGameProfile()
                .getId(),
            group);
        player.addChatMessage(new ChatComponentText("Voice group set to " + group.getDisplayName()));
    }
}
