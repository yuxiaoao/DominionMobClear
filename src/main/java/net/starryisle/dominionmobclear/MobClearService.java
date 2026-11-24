package net.starryisle.dominionmobclear;

import cn.lunadeer.dominion.api.DominionAPI;
import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.flag.EnvFlag;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Scans loaded chunks and clears hostile mobs that are inside a Dominion claim. */
public final class MobClearService {

    public record ScanResult(int chunks, int checked, int removed, long millis, boolean limited) {
    }

    private final DominionMobClearPlugin plugin;
    private DominionAPI api;
    private EnvFlag flag;
    private BukkitTask task;

    private boolean enabled;
    private boolean useFlag;
    private int intervalTicks;
    private int maxChecks;
    private boolean killMode;
    private boolean ignoreNamed;
    private boolean ignoreTamed;
    private boolean ignoreBosses;
    private boolean ignoreWorldwide;
    private int maxIndexedChunks;
    private int indexRefreshSeconds;
    private long lastIndexBuildMillis;
    private final Set<ChunkKey> claimChunks = new HashSet<>();
    private volatile boolean indexDirty = true;
    private boolean useClaimIndex = true;

    /** A chunk covered by at least one player claim. */
    private record ChunkKey(UUID world, int x, int z) {
    }
    private final Set<EntityType> ignoredTypes = EnumSet.noneOf(EntityType.class);
    private final Set<String> blacklistedWorlds = new HashSet<>();

    private volatile ScanResult lastResult = new ScanResult(0, 0, 0, 0, false);
    private volatile long totalRemoved;

    public MobClearService(DominionMobClearPlugin plugin) {
        this.plugin = plugin;
    }

    /** Loads the API and registers the per-claim flag. */
    public boolean init() {
        try {
            api = DominionAPI.getInstance();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("DominionAPI.getInstance() failed: " + throwable.getMessage());
            return false;
        }
        if (api == null) {
            return false;
        }
        reloadSettings();
        registerFlag();
        return true;
    }

    private void registerFlag() {
        EnvFlag candidate = new EnvFlag(
                "hostile_mob_clear_claim",
                "清理敌对生物",
                "开启后，这块领地内的敌对生物会被自动清除。默认关闭，需要按领地在 Dominion 里开启。",
                false,
                true,
                Material.IRON_SWORD);
        try {
            // Disable the old flag from 1.0.x; it used default true and could no
            // longer be turned off reliably because Dominion had persisted it.
            EnvFlag legacy = Flags.getEnvFlag("hostile_mob_clear");
            if (legacy != null) {
                legacy.setEnable(false);
                plugin.getLogger().info("Disabled legacy flag 'hostile_mob_clear'.");
            }
            Flags.registerEnvFlag(plugin, candidate);
            Flags.applyNewCustomFlags();
            EnvFlag existing = Flags.getEnvFlag("hostile_mob_clear_claim");
            flag = existing != null ? existing : candidate;
            plugin.getLogger().info("Dominion flag 'hostile_mob_clear_claim' registered: " + (flag != null));
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to register custom flag: " + throwable.getMessage());
            flag = candidate;
            useFlag = false;
        }
    }

    public void reloadSettings() {
        plugin.reloadConfig();
        enabled = plugin.getConfig().getBoolean("enabled", true);
        useFlag = plugin.getConfig().getBoolean("use-dominion-flag", true);
        intervalTicks = Math.max(20, plugin.getConfig().getInt("interval-ticks", 40));
        maxChecks = Math.max(100, plugin.getConfig().getInt("max-checks-per-sweep", 3000));
        killMode = "kill".equalsIgnoreCase(plugin.getConfig().getString("clear-mode", "remove"));
        ignoreNamed = plugin.getConfig().getBoolean("ignore.named", true);
        ignoreTamed = plugin.getConfig().getBoolean("ignore.tamed", true);
        ignoreBosses = plugin.getConfig().getBoolean("ignore.bosses", true);
        ignoreWorldwide = plugin.getConfig().getBoolean("ignore-worldwide", true);
        maxIndexedChunks = Math.max(100, plugin.getConfig().getInt("max-indexed-chunks", 20000));
        indexRefreshSeconds = Math.max(0, plugin.getConfig().getInt("index-refresh-seconds", 60));
        ignoredTypes.clear();
        for (String raw : plugin.getConfig().getStringList("ignore.types")) {
            try {
                ignoredTypes.add(EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown entity type in ignore.types: " + raw);
            }
        }
        blacklistedWorlds.clear();
        for (String raw : plugin.getConfig().getStringList("worlds.blacklist")) {
            blacklistedWorlds.add(raw.toLowerCase(Locale.ROOT));
        }
    }

    public void start() {
        stop();
        if (!enabled) {
            plugin.getLogger().info("DominionMobClear is disabled in config; no scan task started.");
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweepSafely, intervalTicks, intervalTicks);
        markIndexDirty();
        plugin.getLogger().info("DominionMobClear started: interval=" + intervalTicks
                + " ticks, mode=" + (killMode ? "kill" : "remove")
                + ", flag=" + (useFlag && flag != null));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void restart() {
        reloadSettings();
        start();
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Sweep failed: " + throwable);
        }
    }

    /** Runs one scan synchronously on the server thread. */
    public ScanResult sweep() {
        long start = System.nanoTime();
        if (indexDirty || (indexRefreshSeconds > 0
                && System.currentTimeMillis() - lastIndexBuildMillis >= indexRefreshSeconds * 1000L)) {
            rebuildIndex();
        }
        int[] chunks = {0};
        int[] checked = {0};
        int[] removed = {0};
        boolean limited = false;

        if (useClaimIndex) {
            for (ChunkKey key : claimChunks) {
                World world = Bukkit.getWorld(key.world());
                if (world == null
                        || blacklistedWorlds.contains(world.getName().toLowerCase(Locale.ROOT))
                        || !world.isChunkLoaded(key.x(), key.z())) {
                    continue;
                }
                chunks[0]++;
                if (scanChunk(world.getChunkAt(key.x(), key.z()), checked, removed)) {
                    limited = true;
                    break;
                }
            }
        } else {
            outer:
            for (World world : Bukkit.getWorlds()) {
                if (blacklistedWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                for (Chunk chunk : world.getLoadedChunks()) {
                    chunks[0]++;
                    if (scanChunk(chunk, checked, removed)) {
                        limited = true;
                        break outer;
                    }
                }
            }
        }

        long millis = (System.nanoTime() - start) / 1_000_000L;
        ScanResult result = new ScanResult(chunks[0], checked[0], removed[0], millis, limited);
        lastResult = result;
        totalRemoved += removed[0];
        if (removed[0] > 0 || millis >= 20) {
            plugin.getLogger().info("Sweep: chunks=" + chunks[0] + " checked=" + checked[0]
                    + " removed=" + removed[0] + " time=" + millis + "ms"
                    + (limited ? " (limit reached)" : ""));
        }
        return result;
    }

    /** Scans one chunk; returns true when the per-sweep check budget is reached. */
    private boolean scanChunk(Chunk chunk, int[] checked, int[] removed) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Enemy)) {
                continue;
            }
            if (!entity.isValid() || entity.isDead()) {
                continue;
            }
            if (shouldIgnore(entity)) {
                continue;
            }
            if (checked[0] >= maxChecks) {
                return true;
            }
            checked[0]++;
            Location location = entity.getLocation();
            DominionDTO dominion = api.getDominion(location);
            if (dominion == null) {
                continue;
            }
            // Dominion uses id -1 for the world-wide "dominion"; that is not a
            // player claim, so never clear the whole world because of it.
            if (ignoreWorldwide && dominion.getId() < 0) {
                continue;
            }
            if (!useFlag || flag == null || !api.checkEnvironmentFlag(location, flag)) {
                continue;
            }
            clear(entity);
            removed[0]++;
        }
        return false;
    }

    /** Marks the claim chunk index as stale; it is rebuilt before the next sweep. */
    public void markIndexDirty() {
        indexDirty = true;
    }

    private void rebuildIndex() {
        List<DominionDTO> dominions = api.getAllDominions();
        long estimated = 0;
        for (DominionDTO dom : dominions) {
            if (dom.getId() <= 0) {
                continue;
            }
            UUID worldId = dom.getWorldUid();
            if (worldId == null) {
                continue;
            }
            World world = Bukkit.getWorld(worldId);
            if (world != null && blacklistedWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            CuboidDTO cuboid = dom.getCuboid();
            int minX = Math.min(cuboid.x1(), cuboid.x2()) >> 4;
            int maxX = Math.max(cuboid.x1(), cuboid.x2()) >> 4;
            int minZ = Math.min(cuboid.z1(), cuboid.z2()) >> 4;
            int maxZ = Math.max(cuboid.z1(), cuboid.z2()) >> 4;
            estimated += (long) (maxX - minX + 1) * (maxZ - minZ + 1);
            if (estimated > maxIndexedChunks) {
                claimChunks.clear();
                useClaimIndex = false;
                indexDirty = false;
                plugin.getLogger().warning("Claim chunk index exceeds max-indexed-chunks="
                        + maxIndexedChunks + "; falling back to loaded-chunk scan.");
                return;
            }
        }
        Set<ChunkKey> next = new HashSet<>();
        for (DominionDTO dom : dominions) {
            if (dom.getId() <= 0) {
                continue;
            }
            UUID worldId = dom.getWorldUid();
            if (worldId == null) {
                continue;
            }
            World world = Bukkit.getWorld(worldId);
            if (world != null && blacklistedWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            CuboidDTO cuboid = dom.getCuboid();
            int minX = Math.min(cuboid.x1(), cuboid.x2()) >> 4;
            int maxX = Math.max(cuboid.x1(), cuboid.x2()) >> 4;
            int minZ = Math.min(cuboid.z1(), cuboid.z2()) >> 4;
            int maxZ = Math.max(cuboid.z1(), cuboid.z2()) >> 4;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    next.add(new ChunkKey(worldId, x, z));
                }
            }
        }
        claimChunks.clear();
        claimChunks.addAll(next);
        useClaimIndex = true;
        indexDirty = false;
        lastIndexBuildMillis = System.currentTimeMillis();
    }

    private boolean shouldIgnore(Entity entity) {
        if (ignoreBosses && entity instanceof Boss) {
            return true;
        }
        if (ignoredTypes.contains(entity.getType())) {
            return true;
        }
        if (ignoreNamed && entity.getCustomName() != null) {
            return true;
        }
        return ignoreTamed && entity instanceof Tameable tameable && tameable.isTamed();
    }

    private void clear(Entity entity) {
        if (!killMode) {
            entity.remove();
            return;
        }
        if (entity instanceof LivingEntity living && living.isValid()) {
            living.setHealth(0);
        } else {
            entity.remove();
        }
    }

    public ScanResult lastResult() {
        return lastResult;
    }

    public long totalRemoved() {
        return totalRemoved;
    }

    public boolean enabled() {
        return enabled;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public boolean useFlag() {
        return useFlag && flag != null;
    }

    public EnvFlag flag() {
        return flag;
    }

    public DominionAPI api() {
        return api;
    }

    public boolean isFlagOn(Location location) {
        return flag != null && api != null && api.checkEnvironmentFlag(location, flag);
    }

    /** Whether this claim's own flag is enabled (ignores world-wide flags). */
    public boolean isFlagOn(DominionDTO dominion) {
        return flag != null && Boolean.TRUE.equals(dominion.getEnvFlagValue(flag));
    }

    public boolean killMode() {
        return killMode;
    }
}
