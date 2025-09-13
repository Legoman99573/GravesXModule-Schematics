package dev.cwhead.GravesX.modules.schematics.cmd;

import dev.cwhead.GravesX.module.ModuleContext;
import dev.cwhead.GravesX.module.command.GravesXModuleCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Admin command for the Schematics module to reload its configuration.
 * <p>Usage: <code>/schemgrave reload</code></p>
 * <p>Permission: <code>graves.schem.reload</code></p>
 */
public final class SchemReloadCommand implements GravesXModuleCommand {

    /** Module context used to access plugin and configuration APIs. */
    private final ModuleContext ctx;

    /**
     * Creates a new reload command bound to the given module context.
     *
     * @param ctx module context for config and permission access
     */
    public SchemReloadCommand(ModuleContext ctx) {
        this.ctx = ctx;
    }

    /** {@inheritDoc} */
    @Override public String getName() {
        return "schemgrave";
    }

    /** {@inheritDoc} */
    @Override public String getDescription() {
        return "WorldEdit provider admin";
    }

    /** {@inheritDoc} */
    @Override public String getUsage() {
        return "/schemgrave reload";
    }

    /** {@inheritDoc} */
    @Override public String getPermission() {
        return "graves.schem.reload";
    }

    /**
     * Handles <code>/schemgrave reload</code>. Validates arguments and permission,
     * then reloads the Schematics module configuration.
     *
     * @param sender  command source
     * @param command command metadata
     * @param label   used alias
     * @param args    command arguments
     * @return always {@code true} to indicate the command was handled
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length != 1 || !"reload".equalsIgnoreCase(args[0])) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + getUsage());
            return true;
        }
        if (sender instanceof Player && !ctx.getPlugin().hasGrantedPermission(getPermission(), (Player) sender)) {
            sender.sendMessage(ChatColor.RED + "You lack permission: " + getPermission());
            return true;
        }

        ctx.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "[Schematics] Config reloaded. New graves will use updated settings.");
        return true;
    }
}