package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Optional moving-ship workload in the disposable QA world. Movement uses the
 * real Sable physics pipeline teleport API in its post-physics callback, followed
 * by its normal pose update and network tracking;
 * it is a bounded kinematic workload, not a simulated FPS result or a velocity
 * flight test. The caller measures frame times independently while this runs.
 */
@EventBusSubscriber(modid = "unlockedcamera", value = Dist.CLIENT)
public final class SableMotionProbe {
    private static volatile Context context;
    private static volatile boolean requested;
    private static Consumer<String> log;
    private static int clientTicks;
    private static Object physicsCallback;

    private static final class Context {
        MinecraftServer server;
        ServerLevel level;
        Object ship, pipeline, physics;
        UUID id;
        Method logicalPose, position, orientation, rotationPoint, scale, teleport, resetVelocity,
                addVelocity, updatePose, getLinearVelocity, getAngularVelocity, lastNetworkedPose;
        Vector3d originalPosition, originalPivot, originalLinear, originalAngular;
        Quaterniond originalOrientation;
        Vector3d motionBase;
        Quaterniond motionOrientation;
        Vector3d destination;
        Quaterniond destinationOrientation;
        Throwable callbackFailure;
        int physicsCallbacks;
        long beganAt;
        int ticks;
        int settling = 20;
        int restoring = -1;
        final List<BlockPos> addedBlocks = new ArrayList<>();
        final List<Entity> addedEntities = new ArrayList<>();
    }

    private SableMotionProbe() {}

    public static void start(Minecraft mc, Consumer<String> sink) {
        start(mc, sink, false);
    }

    /** populated=true temporarily grows the ship with air-only planks and four armor stands. */
    public static void start(Minecraft mc, Consumer<String> sink, boolean populated) {
        if (requested || context != null || mc.player == null || mc.getSingleplayerServer() == null) {
            throw new IllegalStateException("Sable motion needs an idle copied singleplayer world");
        }
        requested = true;
        log = sink;
        clientTicks = 0;
        MinecraftServer server = mc.getSingleplayerServer();
        UUID playerId = mc.player.getUUID();
        log.accept("SABLE_MOTION START requested populated=" + populated);
        server.execute(() -> {
            Context candidate = new Context();
            try {
                candidate.server = server;
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || player.isPassenger()) throw new IllegalStateException("Need unmounted player");
                candidate.level = player.serverLevel();
                Class<?> containerType = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                Object container = containerType.getMethod("getContainer", Level.class).invoke(null, candidate.level);
                List<?> ships = (List<?>) containerType.getMethod("getAllSubLevels").invoke(container);
                double nearest = 64.0 * 64.0;
                for (Object ship : ships) {
                    Object pose = ship.getClass().getMethod("logicalPose").invoke(ship);
                    Vector3dc point = (Vector3dc) pose.getClass().getMethod("position").invoke(pose);
                    double distance = player.position().distanceToSqr(point.x(), point.y(), point.z());
                    if (distance < nearest) { nearest = distance; candidate.ship = ship; }
                }
                if (candidate.ship == null) throw new IllegalStateException("No loaded sublevel within 64 blocks");
                Class<?> shipType = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
                Class<?> bodyType = Class.forName("dev.ryanhcode.sable.api.physics.PhysicsPipelineBody");
                Class<?> physicsType = Class.forName("dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem");
                Class<?> pipelineType = Class.forName("dev.ryanhcode.sable.api.physics.PhysicsPipeline");
                candidate.id = (UUID) shipType.getMethod("getUniqueId").invoke(candidate.ship);
                candidate.logicalPose = shipType.getMethod("logicalPose");
                candidate.lastNetworkedPose = shipType.getMethod("lastNetworkedPose");
                Object pose = candidate.logicalPose.invoke(candidate.ship);
                candidate.position = pose.getClass().getMethod("position");
                candidate.orientation = pose.getClass().getMethod("orientation");
                candidate.rotationPoint = pose.getClass().getMethod("rotationPoint");
                candidate.scale = pose.getClass().getMethod("scale");
                candidate.originalPosition = new Vector3d((Vector3dc) candidate.position.invoke(pose));
                candidate.originalPivot = new Vector3d((Vector3dc) candidate.rotationPoint.invoke(pose));
                candidate.originalOrientation = new Quaterniond((Quaterniondc) candidate.orientation.invoke(pose));
                candidate.physics = physicsType.getMethod("get", Level.class).invoke(null, candidate.level);
                if (candidate.physics == null) throw new IllegalStateException("No physics system");
                if ((boolean) physicsType.getMethod("getPaused").invoke(candidate.physics)) {
                    throw new IllegalStateException("Sable physics is paused; no moving workload can run");
                }
                candidate.pipeline = physicsType.getMethod("getPipeline").invoke(candidate.physics);
                candidate.teleport = pipelineType.getMethod("teleport", bodyType, Vector3dc.class, Quaterniondc.class);
                candidate.resetVelocity = pipelineType.getMethod("resetVelocity", bodyType);
                candidate.addVelocity = pipelineType.getMethod("addLinearAndAngularVelocity", bodyType, Vector3dc.class, Vector3dc.class);
                candidate.getLinearVelocity = pipelineType.getMethod("getLinearVelocity", bodyType, Vector3d.class);
                candidate.getAngularVelocity = pipelineType.getMethod("getAngularVelocity", bodyType, Vector3d.class);
                candidate.updatePose = physicsType.getMethod("updatePose", shipType);
                candidate.originalLinear = new Vector3d((Vector3dc) candidate.getLinearVelocity.invoke(candidate.pipeline, candidate.ship, new Vector3d()));
                candidate.originalAngular = new Vector3d((Vector3dc) candidate.getAngularVelocity.invoke(candidate.pipeline, candidate.ship, new Vector3d()));
                // Refuse a ship already in uncontrolled motion; restoring it after
                // an artificial workload would otherwise be ambiguous.
                if (candidate.originalLinear.length() > 2.0 || candidate.originalAngular.length() > 0.5) {
                    throw new IllegalStateException("Existing ship is moving too quickly for reversible QA fixture");
                }
                registerPhysicsCallback();
                candidate.destination = new Vector3d(candidate.originalPosition);
                candidate.destinationOrientation = new Quaterniond(candidate.originalOrientation);
                if (populated) populate(candidate, (Vector3dc) candidate.rotationPoint.invoke(pose));
                context = candidate;
                log.accept("SABLE_MOTION INITIAL ship=" + candidate.id + " pipeline=" + candidate.pipeline.getClass().getName()
                        + " pose=" + pose + " addedBlocks=" + candidate.addedBlocks.size()
                        + " addedEntities=" + candidate.addedEntities.size());
            } catch (Throwable failure) {
                try { removePopulation(candidate); } catch (Throwable ignored) {}
                requested = false;
                log.accept("SABLE_MOTION ERROR start " + failure);
            }
        });
    }

    private static void registerPhysicsCallback() throws Exception {
        if (physicsCallback != null) return;
        // Sable 2.0.6 runs physics before tracking. Its Post callback occurs after
        // each native pose read and before tracking creates the outgoing snapshot.
        // A ServerTick.Post teleport is too late and can be replaced by the next
        // native step before tracking ever observes it. Use the published event
        // seam instead of inventing packets or assigning client render poses.
        Class<?> eventType = Class.forName("dev.ryanhcode.sable.api.event.SablePostPhysicsTickEvent");
        Class<?> platformType = Class.forName("dev.ryanhcode.sable.platform.SableEventPlatform");
        Object callback = Proxy.newProxyInstance(eventType.getClassLoader(), new Class<?>[] {eventType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "UnlockedCamera QA Sable post-physics callback";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Context state = context;
                    if (state != null && args[0] == state.physics && state.callbackFailure == null) {
                        try {
                            applyPose(state, state.destination, state.destinationOrientation);
                            ++state.physicsCallbacks;
                        } catch (Throwable failure) { state.callbackFailure = failure; }
                    }
                    return null;
                });
        platformType.getMethod("onPostPhysicsTick", eventType)
                .invoke(platformType.getField("INSTANCE").get(null), callback);
        physicsCallback = callback;
    }

    private static void populate(Context state, Vector3dc pivot) {
        int centerX = (int) Math.floor(pivot.x()), centerY = (int) Math.floor(pivot.y()), centerZ = (int) Math.floor(pivot.z());
        // Only replace air: existing ship contents and block entities stay intact.
        // Extend behind the seat row (+plot Z), away from the fixed observer
        // platform in front of this copied-world fixture.
        for (int dx = -5; dx <= 5; dx++) for (int dz = 0; dz <= 10; dz++) {
            addPlank(state, new BlockPos(centerX + dx, centerY, centerZ + dz));
        }
        for (int dx = -5; dx <= 5; dx++) for (int dy = 1; dy <= 2; dy++) {
            addPlank(state, new BlockPos(centerX + dx, centerY + dy, centerZ + 10));
        }
        for (int dx : new int[] {-3, 3}) for (int dz : new int[] {3, 7}) {
            ArmorStand stand = EntityType.ARMOR_STAND.create(state.level);
            if (stand == null) throw new IllegalStateException("Cannot create QA armor stand");
            stand.setPos(centerX + dx + 0.5, centerY + 1.0, centerZ + dz + 0.5);
            stand.setNoGravity(true);
            if (state.level.addFreshEntity(stand)) state.addedEntities.add(stand);
        }
    }

    private static void addPlank(Context state, BlockPos pos) {
        if (state.level.getBlockState(pos).isAir()) {
            if (!state.level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), 3)) {
                throw new IllegalStateException("Could not populate plot at " + pos);
            }
            state.addedBlocks.add(pos);
        }
    }

    /** Motion runs on actual server ticks, so lower client FPS cannot slow the workload. */
    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        Context state = context;
        if (state == null || event.getServer() != state.server) return;
        try {
            ++state.ticks;
            if (state.callbackFailure != null) {
                Throwable failure = state.callbackFailure;
                state.callbackFailure = null;
                throw new IllegalStateException("Sable post-physics callback failed", failure);
            }
            if (state.restoring >= 0) {
                if (--state.restoring <= 0) {
                    state.addVelocity.invoke(state.pipeline, state.ship, state.originalLinear, state.originalAngular);
                    log.accept("SABLE_MOTION STOP restoredPose=" + state.logicalPose.invoke(state.ship)
                            + " restoredVelocity=" + state.originalLinear + " angular=" + state.originalAngular);
                    context = null;
                    requested = false;
                }
                return;
            }
            if (state.settling > 0) {
                if (--state.settling == 0) {
                    if (state.physicsCallbacks == 0) throw new IllegalStateException("No Sable post-physics callbacks received");
                    Object pose = state.logicalPose.invoke(state.ship);
                    state.motionBase = new Vector3d(state.originalPosition);
                    state.motionOrientation = new Quaterniond(state.originalOrientation);
                    state.beganAt = state.level.getGameTime();
                    log.accept("SABLE_MOTION READY actualPose=" + pose + " gameTime=" + state.beganAt
                            + " physicsCallbacks=" + state.physicsCallbacks
                            + " lastNetworkedPose=" + state.lastNetworkedPose.invoke(state.ship));
                }
                return;
            }
            double seconds = (state.level.getGameTime() - state.beganAt) / 20.0;
            double phase = seconds * (2.0 * Math.PI / 8.0);
            state.destination.set(state.motionBase).add(0.8 * Math.sin(phase), 0.15 * Math.sin(phase * 0.5), 0.0);
            state.destinationOrientation.set(state.motionOrientation).rotateY(Math.toRadians(10.0) * Math.sin(phase));
            if (state.ticks % 40 == 0) {
                log.accept("SABLE_MOTION SERVER gameTime=" + state.level.getGameTime() + " actualPose="
                        + state.logicalPose.invoke(state.ship) + " bodies=" + state.addedEntities.size()
                        + " physicsCallbacks=" + state.physicsCallbacks
                        + " lastNetworkedPose=" + state.lastNetworkedPose.invoke(state.ship));
            }
        } catch (Throwable failure) {
            log.accept("SABLE_MOTION ERROR tick " + failure);
            if (state.restoring >= 0) {
                log.accept("SABLE_MOTION ERROR restoration failed; inspect copied-world pose before continuing");
                context = null; requested = false;
                return;
            }
            try { beginRestore(state); } catch (Throwable second) {
                log.accept("SABLE_MOTION ERROR restore " + second);
                context = null; requested = false;
            }
        }
    }

    private static void applyPose(Context state, Vector3dc position, Quaterniondc orientation) throws Exception {
        // Adding/removing blocks can move the body's mass-center pivot. Preserve
        // the original plot anchor in world space using Sable's exact transform:
        // world = orientation * ((plot - pivot) * scale) + position.
        Object pose = state.logicalPose.invoke(state.ship);
        Vector3d pivotDelta = new Vector3d((Vector3dc) state.rotationPoint.invoke(pose))
                .sub(state.originalPivot).mul((Vector3dc) state.scale.invoke(pose));
        Vector3d bodyPosition = orientation.transform(pivotDelta).add(position);
        state.resetVelocity.invoke(state.pipeline, state.ship);
        state.teleport.invoke(state.pipeline, state.ship, bodyPosition, orientation);
        state.updatePose.invoke(state.physics, state.ship);
    }

    /** Client-side evidence of real networked movement and plot-space targeting. */
    public static void tick(Minecraft mc) {
        Context state = context;
        if (state == null || ++clientTicks % 40 != 0 || mc.level == null || mc.player == null) return;
        try {
            Class<?> containerType = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Object container = containerType.getMethod("getContainer", Level.class).invoke(null, mc.level);
            Object clientShip = containerType.getMethod("getSubLevel", UUID.class).invoke(container, state.id);
            if (clientShip == null) {
                log.accept("SABLE_MOTION CLIENT not tracking ship=" + state.id);
                return;
            }
            Object logical = clientShip.getClass().getMethod("logicalPose").invoke(clientShip);
            Object rendered = clientShip.getClass().getMethod("renderPose", float.class).invoke(clientShip, 1.0f);
            HitResult hit = mc.hitResult;
            boolean plotHit = hit != null && hit.getType() != HitResult.Type.MISS
                    && hit.getLocation().distanceToSqr(mc.player.position()) > 256.0 * 256.0;
            log.accept("SABLE_MOTION CLIENT logical=" + logical + " render=" + rendered + " plotHit=" + plotHit
                    + " hit=" + (hit == null ? "null" : hit.getLocation()) + " player=" + mc.player.position());
        } catch (Throwable failure) { log.accept("SABLE_MOTION ERROR client " + failure); }
    }

    public static void stop(Minecraft mc) {
        Context state = context;
        if (state == null) return;
        state.server.execute(() -> {
            try { beginRestore(state); }
            catch (Throwable failure) { log.accept("SABLE_MOTION ERROR stop " + failure); }
        });
    }

    private static void beginRestore(Context state) {
        if (state.restoring >= 0) return;
        removePopulation(state);
        state.destination.set(state.originalPosition);
        state.destinationOrientation.set(state.originalOrientation);
        // Let plot mass changes settle before restoring the original velocity.
        state.restoring = 6;
        log.accept("SABLE_MOTION RESTORING removed temporary population");
    }

    private static void removePopulation(Context state) {
        for (Entity entity : state.addedEntities) entity.discard();
        for (BlockPos pos : state.addedBlocks) {
            if (state.level.getBlockState(pos).is(Blocks.OAK_PLANKS)) state.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        state.addedEntities.clear();
        state.addedBlocks.clear();
    }
}
