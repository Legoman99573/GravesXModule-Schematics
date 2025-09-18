package dev.cwhead.GravesX.modules.schematics;

import dev.cwhead.GravesX.api.provider.GraveProvider;
import dev.cwhead.GravesX.module.GravesXModule;
import dev.cwhead.GravesX.module.ModuleContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

/**
 * GravesX module that wires the WorldEdit-backed schematic provider into the plugin.
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #onModuleLoad(ModuleContext)} copies default config and ships the default <code>grave.schem</code>.</li>
 *   <li>{@link #onModuleEnable(ModuleContext)} ensures WorldEdit/FAWE is present and registers a {@link GraveProvider}.</li>
 *   <li>{@link #onModuleDisable(ModuleContext)} releases the provider reference.</li>
 * </ol>
 */
public final class SchematicsModule extends GravesXModule {

    /**
     * The registered schematic provider instance, or {@code null} when disabled or not available.
     */
    private WorldEditSnapshotProvider provider;

    /**
     * Loads module assets: saves the default configuration and ensures the bundled grave schematic exists.
     *
     * @param ctx module context used for file/resource operations
     */
    @Override
    public void onModuleLoad(ModuleContext ctx) {
        ctx.saveDefaultConfig();
        ctx.saveResource("schematics/grave.schem", false);
    }

    /**
     * Enables the module by checking for WorldEdit or FastAsyncWorldEdit and registering the provider service.
     * If neither dependency is present, logs a severe message and skips registration.
     *
     * @param ctx module context used for logging and service registration
     */
    @Override
    public void onModuleEnable(ModuleContext ctx) {
        boolean hasWE = Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
        boolean hasFAWE = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        if (!(hasWE || hasFAWE)) {
            ctx.getLogger().severe("[Schematics] Neither WorldEdit nor FastAsyncWorldEdit is installed.");
            ctx.getGravesXModules().disableModule();
        }
        provider = new WorldEditSnapshotProvider(ctx);
        ctx.registerService(GraveProvider.class, provider, ServicePriority.Normal);
        ctx.getLogger().info("[Schematics] Registered provider: " + provider.id());
    }

    /**
     * Disables the module by clearing the provider reference.
     *
     * @param ctx module context (unused)
     */
    @Override
    public void onModuleDisable(ModuleContext ctx) {
        provider = null;
    }
}