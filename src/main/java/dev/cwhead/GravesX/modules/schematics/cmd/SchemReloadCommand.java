package dev.cwhead.GravesX.modules.schematics.cmd;

import dev.cwhead.GravesX.module.ModuleContext;
import dev.cwhead.GravesX.module.command.GravesXModuleCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class SchemReloadCommand implements GravesXModuleCommand {
    private final ModuleContext ctx;

    public SchemReloadCommand(ModuleContext ctx) {
        this.ctx = ctx;
    }

    @Override public String getName() { return "schemgrave"; }
    @Override public String getDescription() { return "WorldEdit provider admin"; }
    @Override public String getUsage() { return "/schemgrave reload"; }
    @Override public String getPermission() { return "graves.schem.reload"; }

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
