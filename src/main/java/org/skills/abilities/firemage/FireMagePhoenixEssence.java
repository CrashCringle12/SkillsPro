package org.skills.abilities.firemage;

import com.cryptomorin.xseries.XBlock;
import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.cryptomorin.xseries.particles.ParticleDisplay;
import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.skills.abilities.AbilityContext;
import org.skills.abilities.InstantActiveAbility;
import org.skills.abilities.mage.MagePassive;
import org.skills.data.managers.SkilledPlayer;
import org.skills.main.SkillsPro;
import org.skills.managers.DamageManager;
import org.skills.utils.Cooldown;
import org.skills.utils.EntityUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FireMagePhoenixEssence extends InstantActiveAbility {
    private static final Map<Integer, AtomicBoolean> ACITVATED = new HashMap<>();

    static {
        addDisposableHandler(ACITVATED);
    }

    public FireMagePhoenixEssence() {
        super("FireMage", "phoenix_essence");
    }

    public static void forwardSlash(double distance, ParticleDisplay display) {
        new BukkitRunnable() {
            final Vector direction = display.getLocation().getDirection();
            double limit = 0;

            @Override
            public void run() {
                Vector clone = direction.clone().multiply(limit);
                XParticle.ellipse(
                        0, Math.PI,
                        Math.PI / 30,
                        3, 4,
                        display.cloneWithLocation(clone.getX(), clone.getY(), clone.getZ())
                );
                if (limit++ >= distance) cancel();
            }
        }.runTaskTimerAsynchronously(SkillsPro.get(), 1L, 1L);
    }

    public static BukkitTask volcano(int times, long ticks, double radius, ParticleDisplay display) {
        return new BukkitRunnable() {
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = times;

            @Override
            public void run() {
                display.offset(
                        random.nextDouble(-radius, radius),
                        random.nextDouble(0.5, 1),
                        random.nextDouble(-radius, radius)
                ).spawn();
                if (count-- <= 0) cancel();
            }
        }.runTaskTimerAsynchronously(SkillsPro.get(), 0L, ticks);
    }

    private static void thunderTunnel(Location location, double step, Runnable end) {
        new BukkitRunnable() {
            int count = 5;

            @Override
            public void run() {
                XParticle.circle(0.7, 30, ParticleDisplay.simple(location.add(0, step, 0), Particle.FLAME)
                        .directional().withExtra(.07).offset(0, 0, -0.03));
                float pitch = 1f;

                if (count-- == 0) {
                    cancel();
                    pitch = 2f;
                    Bukkit.getScheduler().runTask(SkillsPro.get(), end);
                }

                XSound.BLOCK_CONDUIT_DEACTIVATE.play(location, 10, pitch);
            }
        }.runTaskTimerAsynchronously(SkillsPro.get(), 0L, 5L);
    }

    @EventHandler
    public void onClick(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        AtomicBoolean state = ACITVATED.get(player.getEntityId());
        if (state == null) return;
        if (!MagePassive.isHoe(player.getInventory().getItemInMainHand())) return;

        SkilledPlayer info = SkilledPlayer.getSkilledPlayer(player);
        int lvl = info.getAbilityLevel(this);

        if (event.getAction() == Action.LEFT_CLICK_AIR && lvl >= getScaling(info, "levels.slash")) {
            if (Cooldown.isInCooldown(player.getUniqueId(), "FIREMAGE_SLASH")) return;

            ParticleDisplay display = ParticleDisplay.simple(player.getEyeLocation(), Particle.SOUL_FIRE_FLAME);
            AtomicInteger i = new AtomicInteger();
            double zRot = Math.toRadians(45);
            if (state.getAndSet(!state.get())) {
                zRot = -zRot;
                new Cooldown(player.getUniqueId(), "FIREMAGE_SLASH", (long) getScaling(info, "cooldown.slash"), TimeUnit.SECONDS);
            }
            double slashDamage = getScaling(info, "damage.slash");

            display.face(player)
                    .rotate(0, 0, zRot)
                    .rotationOrder(ParticleDisplay.Axis.X, ParticleDisplay.Axis.Z, ParticleDisplay.Axis.Y)
                    .onSpawn(loc -> {
                        if (i.incrementAndGet() == 5) {
                            i.set(0);
                            Bukkit.getScheduler().runTask(SkillsPro.get(), () -> {
                                for (Entity entity : loc.getWorld().getNearbyEntities(loc, 0.5, 0.5, 0.5)) {
                                    if (EntityUtil.filterEntity(player, entity)) continue;
                                    DamageManager.damage((LivingEntity) entity, player, slashDamage);
                                }
                            });
                        }
                        return true;
                    });

            forwardSlash(getScaling(info, "distance.slash"), display);
            playSound(player, info, "slash");
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR && lvl >= getScaling(info, "levels.volcano")) {
            if (Cooldown.isInCooldown(player.getUniqueId(), "FIREMAGE_VOLCANO")) return;
            new Cooldown(player.getUniqueId(), "FIREMAGE_VOLCANO", (long) getScaling(info, "cooldown.volcano"), TimeUnit.SECONDS);

            Location location = player.getLocation();
            double throwUpRadius = getScaling(info, "radius.volcano");
            new BukkitRunnable() {
                static final double radius = 1;
                final double maxDistance = getScaling(info, "distance.volcano");
                final double throwForce = getScaling(info, "knockback.volcano");
                final Vector directionIgnorePitch = location.getDirection().setY(0).normalize();
                final Vector zipZag = directionIgnorePitch.clone().rotateAroundY(Math.PI / 2);
                double distance = 1;
                boolean odd;

                @Override
                public void run() {
                    double offset = radius * ((odd = !odd) ? -1 : 1);
                    double volcanoDamage = getScaling(info, "damage.volcano");
                    Location loc = location.clone()
                            .add(directionIgnorePitch.clone().multiply(distance))
                            .add(zipZag.clone().multiply(offset));

                    volcano(20, 1, 0.1, ParticleDisplay.simple(loc, Particle.FLAME).directional().withExtra(1));
                    playSound(player, info, "volcano");

                    Bukkit.getScheduler().runTask(SkillsPro.get(), () -> {
                        Block block = loc.getBlock();
                        if (XBlock.isAir(block.getType())) block.setType(Material.FIRE);
                        for (Entity entity : player.getWorld().getNearbyEntities(loc, throwUpRadius, throwUpRadius, throwUpRadius)) {
                            if (EntityUtil.filterEntity(player, entity)) continue;
                            entity.setFireTicks(5 * 20);
                            entity.setVelocity(new Vector(0, throwForce, 0));
                            DamageManager.damage((LivingEntity) entity, player, volcanoDamage);
                        }
                    });

                    if ((distance += 2) >= maxDistance) cancel();
                }
            }.runTaskTimerAsynchronously(SkillsPro.get(), 0L, 3L);
        }
    }

    @Override
    public void useSkill(AbilityContext context) {
        Player player = context.getPlayer();
        playSound(player, context.getInfo(), "music");

        new BukkitRunnable() {
            static final double rand = 10;
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = 100;

            @Override
            public void run() {
                Location location = player.getLocation().add(
                        random.nextDouble(-rand, rand),
                        random.nextDouble(-1, rand),
                        random.nextDouble(-rand, rand)
                );
                ParticleDisplay.display(location, Particle.FLAME);
                if (count-- == 0) {
                    cancel();
                    ACITVATED.put(player.getEntityId(), new AtomicBoolean());
                    Bukkit.getScheduler().runTask(SkillsPro.get(), () -> start(context));
                }
            }
        }.runTaskTimerAsynchronously(SkillsPro.get(), 1L, 1L);
    }

    private void start(AbilityContext context) {
        Player player = context.getPlayer();
        SkilledPlayer info = context.getInfo();

        int lvl = info.getAbilityLevel(this);
        double radius = getScaling(info, "radius.initial");
        double lightningRadius = getScaling(info, "radius.lightning");
        double initialDamage = getScaling(info, "damage.initial");
        double knockback = getScaling(info, "knockback.initial");

        XParticle.sphere(3, 40, ParticleDisplay.simple(player.getLocation(), Particle.FLAME)
                .directional().withExtra(0.5).offset(0.5));

        Location loc = player.getLocation();
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (EntityUtil.filterEntity(player, entity)) continue;
            if (entity instanceof Player) ParticleDisplay.simple(null, Particle.FLASH).spawn(entity.getLocation(), (Player) entity);

            DamageManager.damage((LivingEntity) entity, player, initialDamage);
            EntityUtil.knockBack(entity, loc, knockback);
        }

        SkeletonHorse horse = (SkeletonHorse) player.getWorld().spawnEntity(player.getLocation(), EntityType.SKELETON_HORSE);
        horse.setJumpStrength(5.0);
        horse.getInventory().setSaddle(XMaterial.SADDLE.parseItem());
        horse.setPassenger(player);
        horse.setInvulnerable(true);
        horse.setDomestication(horse.getMaxDomestication());
        applyEffects(info, "horse-effects", horse);

        BukkitTask thunderTask;
        if (lvl >= getScaling(info, "levels.lightning")) {
            thunderTask = new BukkitRunnable() {
                @Override
                public void run() {
                    for (Entity entity : player.getNearbyEntities(lightningRadius, lightningRadius, lightningRadius)) {
                        if (entity == horse) continue;
                        if (EntityUtil.filterEntity(player, entity)) continue;
                        if (entity instanceof Player) ParticleDisplay.simple(null, Particle.FLASH).spawn(entity.getLocation(), (Player) entity);
                        entity.setFireTicks(10 * 20);
                        Location loc = entity.getLocation();
                        thunderTunnel(loc.clone(), 1, () -> entity.getWorld().strikeLightning(loc));
                    }
                }
            }.runTaskTimer(SkillsPro.get(), 5 * 20L, 5 * 20L);
        } else thunderTask = null;

        Bukkit.getScheduler().runTaskLater(SkillsPro.get(), () -> {
            ACITVATED.remove(player.getEntityId());
            if (thunderTask != null) thunderTask.cancel();
            horse.setHealth(0);

            XSound.Record record = getSound(info, "music");
            if (record != null) {
                float volume = record.volume;
                for (Entity entity : loc.getWorld().getNearbyEntities(loc, volume, volume, volume)) {
                    if (entity instanceof Player) record.sound.stopSound((Player) entity);
                }
            }

            playSound(player, info, "end");
        }, (long) (getScaling(info, "duration") * 20L));
    }
}