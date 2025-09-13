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
 * Uses WorldEdit to:
 * 1) snapshot the target region (biome-aware) to a .schem file before building,
 * 2) paste the configured grave schematic (with optional biome overrides),
 * 3) restore the snapshot .schem on removal, or clear the footprint if no snapshot exists.
 */
final class WorldEditSnapshotProvider implements GraveProvider {
    private final ModuleContext ctx;

    private final File graveSchem;
    private final boolean ignoreAir;
    private final int offX, offY, offZ;

    private final boolean snapEnabled;
    private final File snapDir;
    private final boolean useGraveSize;
    private final int boxX, boxY, boxZ;

    private final Material anchorMat;
    private final int anchorOffX, anchorOffY, anchorOffZ;

    private volatile Clipboard graveClipboard;

    private final Map<Biome, File> biomeSchemFiles = new HashMap<>();
    private final Map<Biome, Clipboard> biomeClipboards = new ConcurrentHashMap<>();

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

    @Override public String id() {
        return "worldedit:grave";
    }

    @Override public int order() {
        return 1000;
    }

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

    @Override public boolean supports(EntityData data) {
        return false;
    }
    @Override public boolean removeEntityData(EntityData data) {
        return false;
    }

    private boolean valid(Location loc) {
        return loc == null || loc.getWorld() == null;
    }

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

    private Biome sampleBiomeAt(org.bukkit.World bw, BlockVector3 pos) {
        try {
            return bw.getBiome(pos.x(), pos.y(), pos.z());
        } catch (NoSuchMethodError ignored) {
            return bw.getBiome(pos.getBlockX(), pos.getBlockZ());
        }
    }

    private Clipboard loadClipboard(File file) throws Exception {
        ClipboardFormat fmt = ClipboardFormats.findByFile(file);
        if (fmt == null) throw new IllegalStateException("Unknown clipboard format: " + file.getName());
        try (FileInputStream in = new FileInputStream(file);
             ClipboardReader reader = fmt.getReader(in)) {
            return reader.read();
        }
    }

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