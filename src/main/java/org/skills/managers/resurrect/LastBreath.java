package org.skills.managers.resurrect;

import com.cryptomorin.xseries.particles.ParticleDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.skills.main.SkillsConfig;
import org.skills.main.SkillsPro;
import org.skills.utils.Cooldown;
import org.skills.utils.LocationUtils;
import org.skills.utils.MathUtils;
import org.spigotmc.event.entity.EntityMountEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

import static com.cryptomorin.xseries.ReflectionUtils.*;

public final class LastBreath implements Listener {
    protected static final int VIEW_DISTANCE = 100, ENTITY_POSE_REGISTRY = 6;
    protected static final Map<Integer, LastManStanding> LAST_MEN_STANDING = new HashMap<>(), REVIVERS = new HashMap<>();
    private static final Object ENTITY_POSE_SWIMMING, ENTITY_POSE_STANDING, DATA_WATCHER_REGISTRY;
    private static final MethodHandle PACKET_PLAY_OUT_ENTITY_METADATA, CREATE_DATA_WATCHER, GET_DATA_WATCHER, DATA_WATCHER_SET;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Object entityPoseSwimming = null, entityPoseStanding = null, dataWatcherRegistry = null;
        MethodHandle packetPlayOutEntityMetadata = null, createDataWatcher = null, getDataWatcher = null, dataWatcherSet = null;

        Class<?> entityPose = getNMSClass("world.entity", "EntityPose");
        Class<?> dataWatcher = getNMSClass("network.syncher", "DataWatcher");
        Class<?> entityPlayer = getNMSClass("server.level", "EntityPlayer");
        Class<?> dataWatcherObjectClass = getNMSClass("network.syncher", "DataWatcherObject");
        Class<?> dataWatcherRegistryClass = getNMSClass("network.syncher", "DataWatcherRegistry");
        Class<?> dataWatcherSerializerClass = getNMSClass("network.syncher", "DataWatcherSerializer");
        Class<?> packetPlayOutEntityMetadataClass = getNMSClass("network.protocol.game", "PacketPlayOutEntityMetadata");

        try {
            dataWatcherRegistry = lookup.findStaticGetter(dataWatcherRegistryClass, "s", dataWatcherSerializerClass).invoke();
            entityPoseStanding = entityPose.getDeclaredField(v(17, "a").orElse("STANDING")).get(null);
            entityPoseSwimming = entityPose.getDeclaredField(v(17, "d").orElse("SWIMMING")).get(null);

            getDataWatcher = lookup.findVirtual(entityPlayer, v(18, "ai").orElse("getDataWatcher"), MethodType.methodType(dataWatcher));
            dataWatcherSet = lookup.findVirtual(dataWatcher, v(18, "b").orElse("set"), MethodType.methodType(void.class, dataWatcherObjectClass, Object.class));
            createDataWatcher = lookup.findConstructor(dataWatcherObjectClass,
                    MethodType.methodType(void.class, int.class, dataWatcherSerializerClass));
            packetPlayOutEntityMetadata = lookup.findConstructor(packetPlayOutEntityMetadataClass,
                    MethodType.methodType(void.class, int.class, dataWatcher, boolean.class));
        } catch (Throwable e) {
            e.printStackTrace();
        }

        ENTITY_POSE_SWIMMING = entityPoseSwimming;
        ENTITY_POSE_STANDING = entityPoseStanding;
        DATA_WATCHER_REGISTRY = dataWatcherRegistry;

        CREATE_DATA_WATCHER = createDataWatcher;
        DATA_WATCHER_SET = dataWatcherSet;
        GET_DATA_WATCHER = getDataWatcher;
        PACKET_PLAY_OUT_ENTITY_METADATA = packetPlayOutEntityMetadata;
    }

    static {
        Bukkit.getScheduler().runTaskTimerAsynchronously(SkillsPro.get(), () -> {
            for (LastManStanding lastStanding : LAST_MEN_STANDING.values()) {
                Location location = lastStanding.player.getLocation();
                for (Player player : lastStanding.player.getWorld().getPlayers()) {
                    if (player != lastStanding.player && LocationUtils.distanceSquared(location, player.getLocation()) < VIEW_DISTANCE) {
                        sendPacketSync(player, lastStanding.dataWatcher);
                    }
                }
            }
        }, 100L, 1L);
    }

    protected static Object registerDataWatcher(Player player, boolean swimming) {
        try {
            Object handle = getHandle(player);
            Object watcher = GET_DATA_WATCHER.invoke(handle);
            Object registry = CREATE_DATA_WATCHER.invoke(ENTITY_POSE_REGISTRY, DATA_WATCHER_REGISTRY);

            DATA_WATCHER_SET.invoke(watcher, registry, swimming ? ENTITY_POSE_SWIMMING : ENTITY_POSE_STANDING);
            return PACKET_PLAY_OUT_ENTITY_METADATA.invoke(player.getEntityId(), watcher, true);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    protected static void cover(Player player, Location location) {
        if (supports(13)) player.sendBlockChange(location, Material.BARRIER.createBlockData());
        else player.sendBlockChange(location, Material.BARRIER, (byte) 0);
    }

    private static void cancel(Cancellable event, Entity entity) {
        if (LAST_MEN_STANDING.containsKey(entity.getEntityId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwim(EntityToggleSwimEvent event) {
        cancel(event, event.getEntity());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onStruggle(EntityDamageByEntityEvent event) {
        Entity entity = event.getDamager();
        if (!(entity instanceof Player)) return;
        if (!LAST_MEN_STANDING.containsKey(entity.getEntityId())) return;
        event.setDamage(SkillsConfig.LAST_BREATH_DAMAGE.getDouble());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeathOrDamage(EntityDamageEvent event) {
        switch (event.getCause()) {
            case SUICIDE:
            case SUFFOCATION:
            case LAVA:
            case VOID:
                return;
            case STARVATION:
                cancel(event, event.getEntity());
                break;
        }

        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        double hpLeft = player.getHealth() - event.getFinalDamage();
        if (hpLeft > 0) return; // They're still alive

        // Can't save them again if they die.
        LastManStanding lastMan = LAST_MEN_STANDING.remove(player.getEntityId());
        if (lastMan != null) {
            lastMan.resetState();
            return;
        }
        if (Cooldown.isInCooldown(player.getUniqueId(), "LASTBREATH")) return;
        if (hpLeft > SkillsConfig.LAST_BREATH_INTENSITY_RESISTANCE.getDouble()) return;

        Entity vehicle = player.getVehicle();
        if (vehicle != null) vehicle.eject();

        lastMan = new LastManStanding(player);
        LAST_MEN_STANDING.put(player.getEntityId(), lastMan);

        event.setDamage(Math.max(player.getHealth() - 1.0, 0.01)); // Keep them alive
        cover(player, player.getLocation().add(0, 1, 0));
        new Cooldown(player.getUniqueId(), "LASTBREATH", SkillsConfig.LAST_BREATH_COOLDOWN.getTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        LastManStanding lastMan = LAST_MEN_STANDING.remove(player.getEntityId());
        if (lastMan != null) {
            player.setHealth(0);
            lastMan.resetState();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        cancel(event, event.getEntity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        cancel(event, event.getEntity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() == null) return;
        if (!LAST_MEN_STANDING.containsKey(event.getTarget().getEntityId())) return;
        if (!SkillsConfig.LAST_BREATH_MOBS_IGNORE.getBoolean()) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegenHP(EntityRegainHealthEvent event) {
        cancel(event, event.getEntity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsumeFood(PlayerItemConsumeEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        LastManStanding lastManSuicidal = LAST_MEN_STANDING.get(player.getEntityId()); // removed in die()
        if (lastManSuicidal != null) {
            lastManSuicidal.die();
            return;
        }

        LastManStanding reviver;
        if (!event.isSneaking()) {
            reviver = REVIVERS.remove(player.getEntityId());
            if (reviver != null) {
                reviver.resetProgress();
                ParticleDisplay.simple(player.getLocation(), Particle.SMOKE_LARGE).withCount(30).offset(0.5).spawn();
            }
            return;
        }

        double lastDist = Double.MAX_VALUE;
        LastManStanding closest = null;
        double reviveDist = SkillsConfig.LAST_BREATH_REVIVE_DISTANCE.getDouble();
        Location loc = player.getLocation();
        for (Entity entity : player.getNearbyEntities(reviveDist, reviveDist, reviveDist)) {
            if (!(entity instanceof Player)) continue;
            LastManStanding lastMan = LAST_MEN_STANDING.get(entity.getEntityId());
            if (lastMan != null) {
                double dist = loc.distanceSquared(entity.getLocation());
                if (dist < lastDist) {
                    closest = lastMan;
                    lastDist = dist;
                }
            }
        }

        if (closest == null) return;
        if (closest.reviver != null) return;

        REVIVERS.put(player.getEntityId(), closest);
        closest.reviver = player;
        closest.progress++;
        LastManStanding finLastMan = closest;
        closest.reviveTask = new BukkitRunnable() {
            final ParticleDisplay display = ParticleDisplay.of(Particle.VILLAGER_HAPPY).withCount(20).offset(1);
            final int maxProgress = SkillsConfig.LAST_BREATH_REVIVE_TIME.getInt();

            @Override
            public void run() {
                display.spawn(finLastMan.player.getLocation());

                double progressPercent = MathUtils.getPercent(finLastMan.progress(), maxProgress);
                finLastMan.player.setFoodLevel((int) MathUtils.percentOfAmount(progressPercent, 20));

                if (finLastMan.progress >= maxProgress) {
                    LAST_MEN_STANDING.remove(finLastMan.player.getEntityId());
                    REVIVERS.remove(player.getEntityId());
                    finLastMan.revive();
                    cancel();
                }
            }
        }.runTaskTimer(SkillsPro.get(), 0L, 20L);
    }

    public static boolean isLastBreaths(Player player) {
        return LAST_MEN_STANDING.containsKey(player.getEntityId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (!LocationUtils.hasMovedABlock(event.getFrom(), event.getTo())) return;

        Player player = event.getPlayer();
        if (!isLastBreaths(player)) return;

        Block to = event.getTo().getBlock();
        Block toBarrier = to.getRelative(BlockFace.UP);
        if (toBarrier.getType() == Material.AIR || !toBarrier.getType().isSolid()) {
            if (!to.isLiquid() && !toBarrier.isLiquid()) cover(player, toBarrier.getLocation());

            Block from = event.getFrom().getBlock().getRelative(BlockFace.UP);
            player.sendBlockChange(from.getLocation(), from.getBlockData());
        }
    }
}
