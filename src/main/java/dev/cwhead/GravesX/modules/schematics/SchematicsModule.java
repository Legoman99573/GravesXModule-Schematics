package dev.cwhead.GravesX.modules.schematics;

import dev.cwhead.GravesX.api.provider.GraveProvider;
import dev.cwhead.GravesX.module.GravesXModule;
import dev.cwhead.GravesX.module.ModuleContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

/**
 * Registers the WorldEdit snapshot provider which backs up the region as a .schem
 * before placing the grave schematic, and restores it on removal.
 */
public final class SchematicsModule implements GravesXModule {
    private WorldEditSnapshotProvider provider;

    @Override
    public void onModuleLoad(ModuleContext ctx) {
        ctx.saveDefaultConfig();
        ctx.saveResource("schematics/grave.schem", false);
    }

    @Override
    public void onModuleEnable(ModuleContext ctx) {
        boolean hasWE = Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
        boolean hasFAWE = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        if (!(hasWE || hasFAWE)) {
            ctx.getLogger().severe("[Schematics] Neither WorldEdit nor FastAsyncWorldEdit is installed.");
            return;
        }
        provider = new WorldEditSnapshotProvider(ctx);
        ctx.registerService(GraveProvider.class, provider, ServicePriority.Normal);
        ctx.getLogger().info("[Schematics] Registered provider: " + provider.id());
    }

    @Override
    public void onModuleDisable(ModuleContext ctx) {
        provider = null;
    }
}
