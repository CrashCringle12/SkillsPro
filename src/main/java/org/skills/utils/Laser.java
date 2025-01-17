package org.skills.utils;

import com.cryptomorin.xseries.ReflectionUtils;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.NumberConversions;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cryptomorin.xseries.ReflectionUtils.*;

/**
 * A whole class to create Guardian Beams by reflection </br>
 * Inspired by the API <a href="https://www.spigotmc.org/resources/guardianbeamapi.18329">GuardianBeamAPI</a></br>
 * 1.9+
 * This stimulates the guardian attacking squid pattern.
 *
 * @author SkytAsul
 * @see <a href="https://github.com/SkytAsul/GuardianBeam">GitHub page</a>
 */
public final class Laser {
    /**
     * https://minidigger.github.io/MiniMappingViewer/#/spigot/server/1.16.4/packetplayoutscoreboardteam
     * https://wiki.vg/Protocol#Teams
     * Max length: 16
     */
    private static final String
            TEAM = "skills",
            COLLISION_RULE = "never";
    private static final AtomicInteger TEAM_ID = new AtomicInteger(), LAST_ISSUED_EID = new AtomicInteger(2000000000);

    private final int duration, distanceSquared;
    private final Object createGuardianPacket, createSquidPacket, destroyPacket,
            metadataPacketGuardian, metadataPacketSquid, fakeGuardianDataWatcher, teamCreatePacket;

    private final Map<UUID, Player> players = new ConcurrentHashMap<>(10);
    private final Set<Integer> seen = new HashSet<>();
    private final NMSEntityInfo squid, guardian;
    private Location start, end;
    private BukkitRunnable run;

    /**
     * Create a Laser instance
     *
     * @param start    Location where laser will starts
     * @param end      Location where laser will ends
     * @param duration Duration of laser in seconds (<i>-1 if infinite</i>)
     * @param distance Distance where laser will be visible
     */
    public Laser(Location start, Location end, int duration, int distance) throws ReflectiveOperationException {
        if (start.getWorld() != end.getWorld())
            throw new IllegalArgumentException("Laser start world is different from the end location: " + start.getWorld() + " - " + end.getWorld());

        this.start = start;
        this.end = end;
        this.duration = duration;
        this.distanceSquared = distance * distance;

        Object squid;
        if (supports(17)) {
            squid = NMSReflection.createSquid(end);
            createSquidPacket = NMSReflection.createPacketEntitySpawn(squid);
        } else {
            squid = null;
            createSquidPacket = NMSReflection.createSpawnPacket(end, NMSReflection.SQUID_TYPE);
        }
        UUID squidUUID = (UUID) NMSReflection.getField("b", createSquidPacket);
        int squidId = (int) NMSReflection.getField("a", createSquidPacket);
        this.squid = new NMSEntityInfo(squid, squidUUID, squidId);
        metadataPacketSquid = NMSReflection.createPacketMetadata(squidId, NMSReflection.fakeSquidWatcher);
        NMSReflection.setDirtyWatcher(NMSReflection.fakeSquidWatcher);

        fakeGuardianDataWatcher = NMSReflection.createFakeDataWatcher();
        NMSReflection.initGuardianWatcher(fakeGuardianDataWatcher, squidId);
        Object guardian;
        if (supports(17)) {
            guardian = NMSReflection.createGuardian(start);
            createGuardianPacket = NMSReflection.createPacketEntitySpawn(guardian);
        } else {
            guardian = null;
            createGuardianPacket = NMSReflection.createSpawnPacket(start, NMSReflection.GUARDIAN_TYPE);
        }
        UUID guardianUUID = (UUID) NMSReflection.getField("b", createGuardianPacket);
        int guardianId = (int) NMSReflection.getField("a", createGuardianPacket);
        this.guardian = new NMSEntityInfo(guardian, guardianUUID, guardianId);
        metadataPacketGuardian = NMSReflection.createPacketMetadata(guardianId, fakeGuardianDataWatcher);

        teamCreatePacket = NMSReflection.createPacketTeamAddEntities(squidUUID, guardianUUID);
        destroyPacket = NMSReflection.createPacketRemoveEntities(squidId, guardianId);
    }

    /**
     * To avoid world checks
     */
    private static double distanceSquared(Location start, Location location) {
        return NumberConversions.square(start.getX() - location.getX()) +
                NumberConversions.square(start.getY() - location.getY()) +
                NumberConversions.square(start.getZ() - location.getZ());
    }

    public void start(Plugin plugin) {
        if (run != null) throw new IllegalStateException("Laser is already started");
        run = new BukkitRunnable() {
            final World world = start.getWorld();
            int time = duration;

            @Override
            public void run() {
                for (Player player : world.getPlayers()) {
                    if (isCloseEnough(player.getLocation())) {
                        if (players.put(player.getUniqueId(), player) == null) sendStartPackets(player, !seen.add(player.getEntityId()));
                    } else if (players.remove(player.getUniqueId()) != null) destroy(player);
                }
                if (--time == 0) cancel();
            }

            @Override
            public synchronized void cancel() throws IllegalStateException {
                super.cancel();
                run = null;
                for (Player player : players.values()) destroy(player);
                players.clear();
            }
        };
        run.runTaskTimerAsynchronously(plugin, 0L, 20L);
    }

    public void destroy(Player player) {
        ReflectionUtils.sendPacket(player, destroyPacket);
    }

    public void clear() {
        players.clear();
    }

    public void stop() {
        Validate.isTrue(run != null, "Laser not started");
        run.cancel();
    }

    public void moveStart(Location location) throws ReflectiveOperationException {
        this.start = location;
        Object packet = NMSReflection.teleport(guardian, start);
        for (Player player : players.values()) ReflectionUtils.sendPacket(player, packet);
    }

    public Location getStart() {
        return start;
    }

    public void moveEnd(Location location) throws ReflectiveOperationException {
        // Fixes the weird extra tail.
        this.end = location.add(location.toVector().subtract(start.toVector()).normalize().multiply(-1.5));
        Object packet = NMSReflection.teleport(squid, end);
        for (Player player : players.values()) ReflectionUtils.sendPacket(player, packet);
    }

    public Location getEnd() {
        return end;
    }

    public void callColorChange() {
        for (Player player : players.values()) sendPacket(player, metadataPacketGuardian);
    }

    public boolean isStarted() {
        return run != null;
    }

    private void sendStartPackets(Player player, boolean hasSeen) {
        List<Object> packets = new ArrayList<>(6);

        packets.add(createSquidPacket);
        packets.add(createGuardianPacket);

        if (supports(15)) {
            packets.add(metadataPacketSquid);
            packets.add(metadataPacketGuardian);
        }
        if (!hasSeen) packets.add(teamCreatePacket);
        sendPacketSync(player, packets.toArray());
    }

    private boolean isCloseEnough(Location location) {
        return distanceSquared(start, location) <= distanceSquared ||
                distanceSquared(end, location) <= distanceSquared;
    }

    private static final class NMSEntityInfo {
        private final Object entity;
        private final UUID uuid;
        private final int id;

        private NMSEntityInfo(Object entity, UUID uuid, int id) {
            this.entity = entity;
            this.uuid = uuid;
            this.id = id;
        }
    }

    private static final class NMSReflection {
        private static final MethodHandle PACKET_SPAWN, SET_LOCATION;
        private static final Object NMS_WORLD;
        private static final int TEAM_METHOD_ADD = 3;
        private static final Class<?> packetRemove, packetTeleport, packetTeam, packetMetadata;
        private static Constructor<?> watcherConstructor;

        private static Method watcherSet, watcherRegister, watcherDirty;
        private static Object WATCHER_INVISILIBITY, WATCHER_SPIKES, WATCHER_ATTACK_ID;
        private static Object SQUID_TYPE, GUARDIAN_TYPE;
        private static Object fakeSquid, fakeSquidWatcher;

        static {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Object nmsWorld = null;
            MethodHandle setLocation = null, packetSpawn = null;

            Class<?> craftWorld = getCraftClass("CraftWorld");
            Class<?> entity = getNMSClass("world.entity", "Entity");
            Class<?> entityLiving = getNMSClass("world.entity", "EntityLiving");
            Class<?> guardian = getNMSClass("world.entity.monster", "EntityGuardian");
            Class<?> squid = getNMSClass("world.entity.animal", "EntitySquid");
            Class<?> craftSquid = getCraftClass("entity.CraftSquid");
            Class<?> dataWatcher = getNMSClass("network.syncher", "DataWatcher");
            Class<?> dataWatcherObject = getNMSClass("network.syncher", "DataWatcherObject");
            Class<?> entityTypes = getNMSClass("world.entity", "EntityTypes");
            Class<?> packetSpawnClass = getNMSClass("network.protocol.game", "PacketPlayOutSpawnEntityLiving");

            packetRemove = getNMSClass("network.protocol.game", "PacketPlayOutEntityDestroy");
            packetTeleport = getNMSClass("network.protocol.game", "PacketPlayOutEntityTeleport");
            packetTeam = getNMSClass("network.protocol.game", "PacketPlayOutScoreboardTeam");
            packetMetadata = getNMSClass("network.protocol.game", "PacketPlayOutEntityMetadata");

            try {
                String watcherInvis, watcherSpikes, watcherAttacker;
                if (ReflectionUtils.VER < 13) {
                    watcherInvis = "Z";
                    watcherSpikes = "bA";
                    watcherAttacker = "bB";
                    SQUID_TYPE = 94;
                    GUARDIAN_TYPE = 68;
                } else if (ReflectionUtils.VER == 13) {
                    watcherInvis = "ac";
                    watcherSpikes = "bF";
                    watcherAttacker = "bG";
                    SQUID_TYPE = 70;
                    GUARDIAN_TYPE = 28;
                } else if (ReflectionUtils.VER == 14) {
                    watcherInvis = "W";
                    watcherSpikes = "b";
                    watcherAttacker = "bD";
                    SQUID_TYPE = 73;
                    GUARDIAN_TYPE = 30;
                } else if (ReflectionUtils.VER == 15) {
                    watcherInvis = "T";
                    watcherSpikes = "b";
                    watcherAttacker = "bA";
                    SQUID_TYPE = 74;
                    GUARDIAN_TYPE = 31;
                } else if (ReflectionUtils.VER == 16) {
                    watcherInvis = "S"; // protected static final DataWatcherObject<Byte>    S;
                    watcherSpikes = "b"; // private   static final DataWatcherObject<Boolean> b;
                    watcherAttacker = "d"; // private   static final DataWatcherObject<Integer> d;
                    SQUID_TYPE = 74;
                    GUARDIAN_TYPE = 31;
                } else if (ReflectionUtils.VER == 17) {
                    watcherInvis = "Z";
                    watcherSpikes = "b";
                    watcherAttacker = "e";
                    SQUID_TYPE = entityTypes.getDeclaredField("aJ").get(null); // 86
                    GUARDIAN_TYPE = entityTypes.getDeclaredField("K").get(null); // 35
                } else {
                    watcherInvis = "Z"; // this.Y.b(Z, (byte)(b0 | 1 << i));
                    watcherSpikes = "b";
                    watcherAttacker = "e";
                    SQUID_TYPE = entityTypes.getDeclaredField("aJ").get(null);
                    GUARDIAN_TYPE = entityTypes.getDeclaredField("K").get(null);
                }

                WATCHER_INVISILIBITY = getField(entity, watcherInvis, null);
                WATCHER_SPIKES = getField(guardian, watcherSpikes, null);
                WATCHER_ATTACK_ID = getField(guardian, watcherAttacker, null);

                nmsWorld = craftWorld.getDeclaredMethod("getHandle").invoke(Bukkit.getWorlds().get(0));

                watcherConstructor = dataWatcher.getDeclaredConstructor(entity);
                watcherSet = getMethodStarting(dataWatcher, v(18, "b").orElse("set"), MethodType.methodType(void.class, dataWatcherObject));
                watcherRegister = getMethodStarting(dataWatcher, v(18, "a").orElse("register"), MethodType.methodType(void.class, dataWatcherObject));
                if (supports(15)) watcherDirty = getMethodIgnoreParams(dataWatcher, "markDirty");

                packetSpawn = lookup.findConstructor(packetSpawnClass, supports(17) ? MethodType.methodType(void.class, entityLiving) : MethodType.methodType(void.class));

                if (supports(17))
                    setLocation = lookup.findVirtual(entity,
                            v(18, "a").orElse("setLocation"),
                            MethodType.methodType(void.class, double.class, double.class, double.class, float.class, float.class)
                    );

                Object[] entityConstructorParams = supports(14) ?
                        new Object[]{supports(17) ? SQUID_TYPE : entityTypes.getDeclaredField("SQUID").get(null), nmsWorld} :
                        new Object[]{nmsWorld};
//                fakeSquid = getMethodIgnoreParams(craftSquid, "getHandle")
//                        .invoke(craftSquid.getDeclaredConstructors()[0].newInstance(null,
//                                squid.getDeclaredConstructors()[0].newInstance(entityConstructorParams))
//                        );
                fakeSquid = getNMSClass("world.entity.animal", "EntitySquid").getDeclaredConstructors()[0].newInstance(entityConstructorParams);
                fakeSquidWatcher = createFakeDataWatcher();
                tryWatcherSet(fakeSquidWatcher, WATCHER_INVISILIBITY, (byte) 32);
            } catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }

            NMS_WORLD = nmsWorld;
            SET_LOCATION = setLocation;
            PACKET_SPAWN = packetSpawn;
        }

        private static int generateEID() {
            return LAST_ISSUED_EID.getAndIncrement();
        }

        public static Object createFakeDataWatcher() throws ReflectiveOperationException {
            Object watcher = watcherConstructor.newInstance(fakeSquid);
            if (supports(14)) setField(watcher, "registrationLocked", false);
            return watcher;
        }

        public static void setDirtyWatcher(Object watcher) throws ReflectiveOperationException {
            if (supports(15)) watcherDirty.invoke(watcher, WATCHER_INVISILIBITY);
        }

        public static Object createSquid(Location location) throws ReflectiveOperationException {
            Object entity = getNMSClass("world.entity.animal", "EntitySquid").getDeclaredConstructors()[0]
                    .newInstance(SQUID_TYPE, NMS_WORLD);
            setLocation(entity, location);
            return entity;
        }

        public static Object createGuardian(Location location) throws ReflectiveOperationException {
            Object entity = getNMSClass("world.entity.monster", "EntityGuardian").getDeclaredConstructors()[0]
                    .newInstance(GUARDIAN_TYPE, NMS_WORLD);
            setLocation(entity, location);
            return entity;
        }

        public static Object createSpawnPacket(Location location, Object entityType) {
            try {
                Object packet = PACKET_SPAWN.invoke();
                setField(packet, "a", generateEID());
                setField(packet, "b", UUID.randomUUID());
                setField(packet, "c", entityType);
                setField(packet, "d", location.getX());
                setField(packet, "e", location.getY());
                setField(packet, "f", location.getZ());
                setField(packet, "j", (byte) (location.getYaw() * 256.0F / 360.0F));
                setField(packet, "k", (byte) (location.getPitch() * 256.0F / 360.0F));
                if (!supports(15)) setField(packet, "m", fakeSquidWatcher);

                return packet;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                return null;
            }
        }

        public static void initGuardianWatcher(Object watcher, int squidId) throws ReflectiveOperationException {
            tryWatcherSet(watcher, WATCHER_INVISILIBITY, (byte) 32);
            tryWatcherSet(watcher, WATCHER_SPIKES, false);
            tryWatcherSet(watcher, WATCHER_ATTACK_ID, squidId);
        }

        public static Object createPacketEntitySpawn(Object entity) {
            try {
                return PACKET_SPAWN.invoke(entity);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                return null;
            }
        }

        @SuppressWarnings("PrimitiveArrayArgumentToVarargsMethod")
        public static Object createPacketRemoveEntities(int squidId, int guardianId) throws ReflectiveOperationException {
            if (ReflectionUtils.supports(17)) {
                Constructor<?> ctor = packetRemove.getConstructor(int[].class);
                return ctor.newInstance(new int[]{squidId, guardianId});
            }

            Object packet = packetRemove.newInstance();
            setField(packet, "a", new int[]{squidId, guardianId});
            return packet;
        }

        public static void setLocation(Object entity, Location location) {
            try {
                SET_LOCATION.invoke(entity, location.getX(), location.getY(), location.getZ(), location.getPitch(), location.getYaw());
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }

        @SuppressWarnings("unchecked")
        public static Object createPacketTeamAddEntities(UUID squidUUID, UUID guardianUUID) throws ReflectiveOperationException {
            Object packet;

            String id = TEAM + TEAM_ID.getAndIncrement();
            if (supports(17)) {
                Collection<String> players = new ArrayList<>(2);
                players.add(squidUUID.toString());
                players.add(guardianUUID.toString());

                Constructor<?> ctor = packetTeam.getDeclaredConstructor(String.class, int.class, Optional.class, Collection.class);
                ctor.setAccessible(true);
                packet = ctor.newInstance(id, TEAM_METHOD_ADD, Optional.empty(), players);
            } else {
                packet = packetTeam.newInstance();
                setField(packet, "a", id);
                setField(packet, "f", COLLISION_RULE);
                setField(packet, "i", TEAM_METHOD_ADD);

                Collection<String> players = (Collection<String>) getField(packetTeam, "h", packet);
                players.add(squidUUID.toString());
                players.add(guardianUUID.toString());
            }

            return packet;
        }

        public static Object teleport(NMSEntityInfo entityInfo, Location location) throws ReflectiveOperationException {
            Object packet;

            if (supports(17)) {
                try {
                    setLocation(entityInfo.entity, location);
                    packet = packetTeleport.getConstructor(ReflectionUtils.getNMSClass("world.entity", "Entity")).newInstance(entityInfo.entity);
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                    return null;
                }
            } else {
                packet = packetTeleport.newInstance();
                setField(packet, "a", entityInfo.id);
                setField(packet, "b", location.getX());
                setField(packet, "c", location.getY());
                setField(packet, "d", location.getZ());
                setField(packet, "e", (byte) (location.getYaw() * 256.0F / 360.0F));
                setField(packet, "f", (byte) (location.getPitch() * 256.0F / 360.0F));
                setField(packet, "g", true);
            }

            return packet;
        }

        private static Object createPacketMetadata(int entityId, Object watcher) throws ReflectiveOperationException {
            return packetMetadata.getConstructor(int.class, watcher.getClass(), boolean.class).newInstance(entityId, watcher, false);
        }

        private static void tryWatcherSet(Object watcher, Object watcherObject, Object watcherData) throws ReflectiveOperationException {
            try {
                watcherSet.invoke(watcher, watcherObject, watcherData);
            } catch (InvocationTargetException ex) {
                watcherRegister.invoke(watcher, watcherObject, watcherData);
                if (supports(15)) watcherDirty.invoke(watcher, watcherObject);
            }
        }

        private static Method getMethodIgnoreParams(Class<?> clazz, String name) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(name)) return method;
            }
            return null;
        }

        private static Method getMethodStarting(Class<?> clazz, String name, MethodType type) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getReturnType() != type.returnType()) continue;
                if (type.parameterCount() != 0 && type.parameterCount() <= method.getParameterCount()) {
                    int i = 0;
                    Parameter[] params = method.getParameters();
                    for (Class<?> param : type.parameterArray()) {
                        if (params[i++].getType() != param) {
                            i = -1;
                            break;
                        }
                    }
                    if (i == -1) continue;
                }

                method.setAccessible(true);
                return method;
            }
            return null;
        }

        private static void setField(Object instance, String name, Object value) throws ReflectiveOperationException {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        }

        private static Object getField(Class<?> clazz, String name, Object instance) throws ReflectiveOperationException {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        }

        private static Object getField(String name, Object instance) throws ReflectiveOperationException {
            return getField(instance.getClass(), name, instance);
        }
    }
}
