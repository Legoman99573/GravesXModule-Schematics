package dev.cwhead.GravesX.modules.schematics;

import com.ranull.graves.data.EntityData;
import com.ranull.graves.type.Grave;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import dev.cwhead.GravesX.api.provider.GraveProvider;
import dev.cwhead.GravesX.module.ModuleContext;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldEdit-backed schematic provider that can snapshot, paste, and restore grave builds.
 * <p>Flow:</p>
 * <ol>
 *   <li>Optionally snapshot the target region to a <code>.schem</code>.</li>
 *   <li>Paste the grave schematic (biome-aware overrides supported).</li>
 *   <li>On removal, restore the snapshot or clear the pasted footprint.</li>
 * </ol>
 */
final class WorldEditSnapshotProvider implements GraveProvider {

    /** Module context for logging, config, and scheduler access. */
    private final ModuleContext ctx;

    /** Default grave schematic file. */
    private final File graveSchem;
    /** If true, air blocks in the schematic are ignored when pasting. */
    private final boolean ignoreAir;
    /** World paste offsets. */
    private final int offX, offY, offZ;

    /** Enables capture and restore of the pre-paste region. */
    private final boolean snapEnabled;
    /** Directory to store snapshot schematics. */
    private final File snapDir;
    /** If true, snapshot region matches clipboard size; otherwise a fixed box is used. */
    private final boolean useGraveSize;
    /** Fixed snapshot box size (used when {@link #useGraveSize} is false). */
    private final int boxX, boxY, boxZ;

    /** Anchor material used to mark that a paste occurred. */
    private final Material anchorMat;
    /** Anchor block offsets relative to the grave location. */
    private final int anchorOffX, anchorOffY, anchorOffZ;

    /** Default grave clipboard (loaded from {@link #graveSchem}). */
    private volatile Clipboard graveClipboard;

    /** Mapping of biome to override schematic file. */
    private final Map<Biome, File> biomeSchemFiles = new HashMap<>();
    /** Cache of loaded override clipboards by biome. */
    private final Map<Biome, Clipboard> biomeClipboards = new ConcurrentHashMap<>();

    /**
     * Creates a provider and reads configuration (schematics, snapshot, and anchor settings).
     *
     * @param ctx module context providing config, logger, and scheduling utilities
     */
    WorldEditSnapshotProvider(ModuleContext ctx) {
        this.ctx = ctx;

        this.graveSchem = new File(ctx.getDataFolder(), ctx.getConfig().getString("schematic.grave", "schematics/grave.schem"));
        this.ignoreAir = ctx.getConfig().getBoolean("paste.ignore-air", true);
        this.offX = ctx.getConfig().getInt("paste.offset.x", 0);
        this.offY = ctx.getConfig().getInt("paste.offset.y", 0);
        this.offZ = ctx.getConfig().getInt("paste.offset.z", 0);

        this.snapEnabled = ctx.getConfig().getBoolean("snapshot.enabled", true);
        this.snapDir = new File(ctx.getDataFolder(), ctx.getConfig().getString("snapshot.dir", "backups"));
        if (!snapDir.exists()) snapDir.mkdirs();
        this.useGraveSize = ctx.getConfig().getBoolean("snapshot.use-grave-size", true);
        this.boxX = Math.max(1, ctx.getConfig().getInt("snapshot.box.x", 5));
        this.boxY = Math.max(1, ctx.getConfig().getInt("snapshot.box.y", 4));
        this.boxZ = Math.max(1, ctx.getConfig().getInt("snapshot.box.z", 5));

        Material mat;
        try {
            mat = Material.valueOf(ctx.getConfig().getString("anchor.material", "POLISHED_BLACKSTONE_BUTTON").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mat = Material.POLISHED_BLACKSTONE_BUTTON;
        }
        this.anchorMat = mat;
        this.anchorOffX = ctx.getConfig().getInt("anchor.offset.x", 0);
        this.anchorOffY = ctx.getConfig().getInt("anchor.offset.y", 0);
        this.anchorOffZ = ctx.getConfig().getInt("anchor.offset.z", 0);

        java.util.List<String> biomeOverrides = ctx.getConfig().getStringList("schematic.override.biomes");
        for (String line : biomeOverrides) {
            if (line == null || line.isBlank()) continue;
            String[] parts = line.split(":", 2);
            if (parts.length < 2) {
                ctx.getLogger().warning("[Schematics] Invalid biome override entry (missing ':'): " + line);
                continue;
            }

            String biomeKey = parts[0].trim();
            String path = parts[1].trim();

            Biome biome = resolveBiome(biomeKey);
            if (biome == null) {
                ctx.getLogger().warning("[Schematics] Unknown biome in override: " + biomeKey + " (entry: " + line + ")");
                continue;
            }

            File f = new File(path);
            if (!f.isAbsolute()) f = new File(ctx.getDataFolder(), path);
            biomeSchemFiles.put(biome, f);
        }

        loadGraveClipboard();
    }

    /**
     * Resolves a {@link Biome} from a key like {@code minecraft:plains}, {@code PLAINS}, or {@code plains}.
     *
     * @param raw biome key string
     * @return resolved biome or {@code null} if unknown
     */
    private Biome resolveBiome(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String normalized = raw.trim().replace(' ', '_').replace('-', '_');
        NamespacedKey key = normalized.contains(":")
                ? NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT))
                : NamespacedKey.fromString("minecraft:" + normalized.toLowerCase(Locale.ROOT));

        try {
            Object server = Bukkit.getServer();
            Object registryAccess = server.getClass().getMethod("getRegistryAccess").invoke(server);

            Class<?> registryKeyCls = Class.forName("org.bukkit.RegistryKey");
            Object BIOME_KEY = registryKeyCls.getField("BIOME").get(null);

            Object registry = registryAccess.getClass()
                    .getMethod("getRegistry", registryKeyCls)
                    .invoke(registryAccess, BIOME_KEY);

            if (key != null) {
                Biome viaAccess = (Biome) registry.getClass()
                        .getMethod("get", NamespacedKey.class)
                        .invoke(registry, key);
                if (viaAccess != null) return viaAccess;
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> registryCls = Class.forName("org.bukkit.Registry");
            Object biomeRegistry = registryCls.getField("BIOME").get(null);
            if (biomeRegistry != null && key != null) {
                Biome viaDeprecated = (Biome) biomeRegistry.getClass()
                        .getMethod("get", NamespacedKey.class)
                        .invoke(biomeRegistry, key);
                if (viaDeprecated != null) return viaDeprecated;
            }
        } catch (Throwable ignored) {
        }

        try {
            return Biome.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override public String id() {
        return "worldedit:grave";
    }

    /** {@inheritDoc} */
    @Override public int order() {
        return 1000;
    }

    /**
     * Pastes the grave schematic at the given location and writes a snapshot beforehand if enabled.
     *
     * @param loc   paste base location (usually the death location)
     * @param grave grave metadata used for snapshot naming and context
     */
    @Override
    public void place(Location loc, Grave grave) {
        if (valid(loc)) return;

        final org.bukkit.World bw = loc.getWorld();
        final World weWorld = BukkitAdapter.adapt(bw);

        final BlockVector3 pasteTo = BlockVector3.at(
                loc.getBlockX() + offX,
                loc.getBlockY() + offY,
                loc.getBlockZ() + offZ
        );

        final Clipboard activeClip = resolveClipboardForBiome(bw, pasteTo);
        if (activeClip == null) {
            ctx.getLogger().warning("[Schematics] No grave schematic available to paste.");
            return;
        }

        final CuboidRegion snapRegion = useGraveSize
                ? regionAlignedToClipboard(activeClip, pasteTo)
                : regionFromBoxAt(pasteTo, boxX, boxY, boxZ);

        final File snapFile = new File(snapDir, grave.getUUID().toString() + ".schem");

        ctx.runTask(() -> {
            if (snapEnabled) {
                try {
                    if (snapRegion.getVolume() == 0) {
                        ctx.getLogger().warning("[Schematics] Snapshot region is empty; skipping backup.");
                    } else {
                        saveRegionToSchematic(weWorld, snapRegion, snapFile);
                    }
                } catch (Throwable t) {
                    ctx.getLogger().warning("[Schematics] Snapshot failed: " + t.getMessage());
                }
            }

            try (EditSession edit = WorldEdit.getInstance().newEditSession(weWorld)) {
                ClipboardHolder holder = new ClipboardHolder(activeClip);
                Operation op = holder.createPaste(edit)
                        .to(pasteTo)
                        .ignoreAirBlocks(ignoreAir)
                        .build();
                Operations.complete(op);
            } catch (Throwable t) {
                ctx.getLogger().warning("[Schematics] Paste failed: " + t.getMessage());
            }

            Block anchor = bw.getBlockAt(
                    loc.getBlockX() + anchorOffX,
                    loc.getBlockY() + anchorOffY,
                    loc.getBlockZ() + anchorOffZ
            );
            if (anchor.getType() != anchorMat) anchor.setType(anchorMat, false);
        });
    }

    /**
     * Removes a previously pasted grave by restoring a snapshot or clearing the pasted region.
     *
     * @param grave grave whose region should be restored or cleared
     */
    @Override
    public void remove(Grave grave) {
        Location loc = grave.getLocationDeath();
        if (valid(loc)) return;

        final org.bukkit.World bw = loc.getWorld();
        final World weWorld = BukkitAdapter.adapt(bw);

        final BlockVector3 pasteTo = BlockVector3.at(
                loc.getBlockX() + offX,
                loc.getBlockY() + offY,
                loc.getBlockZ() + offZ
        );

        final File snapFile = new File(snapDir, grave.getUUID().toString() + ".schem");

        ctx.runTask(() -> {
            boolean restored = false;

            if (snapEnabled && snapFile.exists()) {
                Clipboard activeClip = resolveClipboardForBiome(bw, pasteTo);
                CuboidRegion snapRegion = (activeClip != null)
                        ? regionAlignedToClipboard(activeClip, pasteTo)
                        : regionFromBoxAt(pasteTo, boxX, boxY, boxZ);

                Clipboard snap = null;
                try { snap = loadClipboard(snapFile); }
                catch (Throwable t) {
                    ctx.getLogger().warning("[Schematics] Failed to load snapshot; clearing instead: " + t.getMessage());
                }

                if (snap != null) {
                    BlockVector3 restoreAt = snapRegion.getMinimumPoint();
                    try (EditSession edit = WorldEdit.getInstance().newEditSession(weWorld)) {
                        ClipboardHolder holder = new ClipboardHolder(snap);
                        Operation op = holder.createPaste(edit)
                                .to(restoreAt)
                                .ignoreAirBlocks(false) // exact restoration
                                .build();
                        Operations.complete(op);
                        restored = true;
                    } catch (Throwable t) {
                        ctx.getLogger().warning("[Schematics] Snapshot paste failed: " + t.getMessage());
                    }
                    if (restored && !snapFile.delete()) snapFile.deleteOnExit();
                }
            }

            if (!restored) {
                Clipboard activeClip = resolveClipboardForBiome(bw, pasteTo);
                CuboidRegion clear = (activeClip != null)
                        ? regionAlignedToClipboard(activeClip, pasteTo)
                        : regionFromBoxAt(pasteTo, boxX, boxY, boxZ);

                try (EditSession edit = WorldEdit.getInstance().newEditSession(weWorld)) {
                    edit.setBlocks(clear, com.sk89q.worldedit.world.block.BlockTypes.AIR.getDefaultState());
                } catch (Throwable t) {
                    ctx.getLogger().warning("[Schematics] Clear failed: " + t.getMessage());
                }
            }

            Block anchor = bw.getBlockAt(
                    loc.getBlockX() + anchorOffX,
                    loc.getBlockY() + anchorOffY,
                    loc.getBlockZ() + anchorOffZ
            );
            if (anchor.getType() == anchorMat) {
                anchor.setType(Material.AIR, false);
            }
        });
    }

    /**
     * Checks whether the grave is considered placed by testing the anchor block.
     *
     * @param grave grave to test
     * @return true if the anchor block matches {@link #anchorMat}
     */
    @Override
    public boolean isPlaced(Grave grave) {
        Location loc = grave.getLocationDeath();
        if (valid(loc)) return false;
        Block b = loc.getWorld().getBlockAt(
                loc.getBlockX() + anchorOffX,
                loc.getBlockY() + anchorOffY,
                loc.getBlockZ() + anchorOffZ
        );
        return b.getType() == anchorMat;
    }

    /**
     * This provider does not manage entity data.
     *
     * @param data entity data
     * @return always false
     */
    @Override public boolean supports(EntityData data) {
        return false;
    }

    /**
     * This provider does not manage entity data.
     *
     * @param data entity data
     * @return always false
     */
    @Override public boolean removeEntityData(EntityData data) {
        return false;
    }

    /**
     * Returns true if the location or its world is null.
     *
     * @param loc location to validate
     * @return true if invalid
     */
    private boolean valid(Location loc) {
        return loc == null || loc.getWorld() == null;
    }

    /** Loads the default grave clipboard from disk, logging on failure. */
    private void loadGraveClipboard() {
        if (!graveSchem.exists()) {
            ctx.getLogger().warning("[Schematics] Missing grave schematic: " + graveSchem.getPath());
            graveClipboard = null;
            return;
        }
        try { graveClipboard = loadClipboard(graveSchem); }
        catch (Throwable t) {
            ctx.getLogger().warning("[Schematics] Failed to read grave schematic: " + t.getMessage());
            graveClipboard = null;
        }
    }

    /**
     * Resolves the clipboard to use at a position, honoring biome overrides.
     *
     * @param bw          Bukkit world
     * @param pasteAnchor paste anchor position
     * @return clipboard for the biome or the default clipboard
     */
    private Clipboard resolveClipboardForBiome(org.bukkit.World bw, BlockVector3 pasteAnchor) {
        if (biomeSchemFiles.isEmpty()) return graveClipboard;

        Biome biome = sampleBiomeAt(bw, pasteAnchor);
        File f = (File) biomeSchemFiles.get(biome);
        if (f == null) return graveClipboard;

        Clipboard cached = biomeClipboards.get(biome);
        if (cached != null) return cached;

        if (!f.exists()) {
            ctx.getLogger().warning("[Schematics] Biome override schematic missing for " + biome + ": " + f.getPath() + " (using default)");
            return graveClipboard;
        }
        try {
            Clipboard clip = loadClipboard(f);
            biomeClipboards.put(biome, clip);
            ctx.getLogger().info("[Schematics] Using biome override for " + biome + ": " + f.getPath());
            return clip;
        } catch (Throwable t) {
            ctx.getLogger().warning("[Schematics] Failed to load biome override for " + biome + " from " + f.getPath() + ": " + t.getMessage());
            return graveClipboard;
        }
    }

    /**
     * Samples the biome at the given position, supporting legacy API where needed.
     *
     * @param bw  Bukkit world
     * @param pos position to sample
     * @return biome at position
     */
    private Biome sampleBiomeAt(org.bukkit.World bw, BlockVector3 pos) {
        try {
            return bw.getBiome(pos.x(), pos.y(), pos.z());
        } catch (NoSuchMethodError ignored) {
            return bw.getBiome(pos.getBlockX(), pos.getBlockZ());
        }
    }

    /**
     * Loads a clipboard from a file using WorldEdit's format detection.
     *
     * @param file .schem file
     * @return loaded clipboard
     * @throws Exception if reading fails or format is unknown
     */
    private Clipboard loadClipboard(File file) throws Exception {
        ClipboardFormat fmt = ClipboardFormats.findByFile(file);
        if (fmt == null) throw new IllegalStateException("Unknown clipboard format: " + file.getName());
        try (FileInputStream in = new FileInputStream(file);
             ClipboardReader reader = fmt.getReader(in)) {
            return reader.read();
        }
    }

    /**
     * Saves a region from the world into a schematic file, including biomes and entities.
     *
     * @param weWorld worldedit world
     * @param region  region to copy
     * @param outFile destination file
     * @throws Exception if copying or writing fails
     */
    private void saveRegionToSchematic(World weWorld, CuboidRegion region, File outFile) throws Exception {
        Clipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(region.getMinimumPoint());

        try (EditSession source = WorldEdit.getInstance().newEditSession(weWorld)) {
            ForwardExtentCopy copy = new ForwardExtentCopy(source, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(true);
            copy.setCopyingBiomes(true);
            Operations.complete(copy);
        }

        ClipboardFormat fmt = ClipboardFormats.findByFile(outFile);
        if (fmt == null) {
            try {
                try {
                    fmt = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC;
                } catch (Exception e) {
                    fmt = BuiltInClipboardFormat.SPONGE_V2_SCHEMATIC;
                }
            } catch (Exception e) {
                fmt = BuiltInClipboardFormat.SPONGE_SCHEMATIC;
            }
        }

        try (FileOutputStream fos = new FileOutputStream(outFile);
             ClipboardWriter writer = fmt.getWriter(fos)) {
            writer.write(clipboard);
        }
    }

    /**
     * Builds a region in world space that aligns to the bounds of a clipboard at a paste anchor.
     *
     * @param clip        clipboard to align with
     * @param pasteAnchor origin for alignment
     * @return aligned cuboid region
     */
    private CuboidRegion regionAlignedToClipboard(Clipboard clip, BlockVector3 pasteAnchor) {
        BlockVector3 clipMin = clip.getRegion().getMinimumPoint();
        BlockVector3 clipMax = clip.getRegion().getMaximumPoint();
        BlockVector3 clipOrigin = clip.getOrigin();

        BlockVector3 deltaMin = clipMin.subtract(clipOrigin);
        BlockVector3 deltaMax = clipMax.subtract(clipOrigin);

        BlockVector3 worldMin = pasteAnchor.add(deltaMin);
        BlockVector3 worldMax = pasteAnchor.add(deltaMax);
        return new CuboidRegion(worldMin, worldMax);
    }

    /**
     * Builds a fixed-size region whose minimum corner starts at the given position.
     *
     * @param to anchor position
     * @param sx size X
     * @param sy size Y
     * @param sz size Z
     * @return cuboid region with the specified size
     */
    private CuboidRegion regionFromBoxAt(BlockVector3 to, int sx, int sy, int sz) {
        BlockVector3 min = to;
        BlockVector3 max;
        try {
            max = BlockVector3.at(to.x() + sx - 1, to.y() + sy - 1, to.z() + sz - 1);
        } catch (Exception e) {
            max = BlockVector3.at(to.getBlockX() + sx - 1, to.getBlockY() + sy - 1, to.getBlockZ() + sz - 1);
        }
        return new CuboidRegion(min, max);
    }
}