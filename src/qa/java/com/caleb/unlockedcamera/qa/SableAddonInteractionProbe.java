package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.AddonInteractionAim;
import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Eighteen real addon requests on one existing plot, plus four bounded aim-priority observations. */
public final class SableAddonInteractionProbe {
    private static final String MODULE = "aeroworks:button_module";
    private static final String CONSOLE = "aeroworks:control_desk";
    private static final String WIRE = "powergrid:wire";
    private static final String CONNECTOR = "powergrid:wire_connector";
    private static final String[] MODES = {"first_person", "offset", "freelook"};
    private static final Map<Field, Object> savedCamera = new LinkedHashMap<>();
    private static final Map<BlockPos, BlockState> savedBlocks = new LinkedHashMap<>();
    private static final Map<String, Boolean> controls = new HashMap<>();
    private static boolean registered, running, restoring, motionStarted, movingStarted, abortAfterDrain;
    private static boolean priorityFrozen;
    private static volatile boolean motionError;
    private static volatile boolean motionStopSeen;
    private static volatile double motionRestoreError;
    private static boolean oldToggle, oldShoulder, oldFreeEnabled, oldKeep, oldFreeKey, oldShift, oldPause, oldUse;
    private static double oldAmount, oldMax;
    private static CameraType oldType;
    private static KeyMapping freeKey;
    private static Consumer<String> log;
    private static CompletableFuture<Void> pending;
    private static Snapshot snapshot;
    private static Set<UUID> originals;
    private static Object serverShip, clientShip, moduleType;
    private static UUID shipId;
    private static BlockPos plotTarget;
    private static Vec3 localAim, initialWorld, fixturePosition;
    private static Class<?> consoleClass, moduleTypeClass;
    private static Method setCameraPosition, setCameraRotation, transform;
    private static DataComponentType<?> connectionType;
    private static int index, phase, age, checks, failures, limited, socket, restorePhase;
    private static float bodyYaw, bodyPitch;
    private static volatile int posePlayerTick;
    private static volatile boolean poseSettled, serverEffect, serverReady, stateRestored;
    private static volatile String detail;
    private static String clientResult, finishReason;
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode, boolean flying,
                            int selected, List<ItemStack> inventory) {}
    @FunctionalInterface private interface ServerAction { void run(ServerPlayer player) throws Exception; }
    private SableAddonInteractionProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if ((!game.endsWith(Path.of("build", "modpack-test", "game")) && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
                || running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isPassenger() || mc.player.isUsingItem())
            throw new IllegalStateException("Idle disposable integrated client required");
        if (field(SableMotionProbe.class, "context").get(null) != null || field(SableMotionProbe.class, "requested").getBoolean(null))
            throw new IllegalStateException("Existing Sable motion owner");
        consoleClass = Class.forName("com.mred231.aeroworks.content.controls.console.ConsoleBlockEntity");
        moduleTypeClass = Class.forName("com.mred231.aeroworks.content.controls.module.ModuleType");
        moduleType = item(MODULE).getItem().getClass().getMethod("getType").invoke(item(MODULE).getItem());
        transform = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc").getMethod("transformPosition", Vec3.class);
        Object supplier = Class.forName("org.patryk3211.powergrid.collections.ModdedDataComponents").getField("CONNECTION_DATA").get(null);
        connectionType = (DataComponentType<?>) Class.forName("dev.architectury.registry.registries.RegistrySupplier").getMethod("get").invoke(supplier);
        setCameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class); setCameraPosition.setAccessible(true);
        setCameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); setCameraRotation.setAccessible(true);
        savedCamera.clear(); savedBlocks.clear(); controls.clear();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "shoulderSide", "freelookYaw", "freelookPitch"}) {
            Field f = field(UnlockedCameraClient.class, name); savedCamera.put(f, f.get(null));
        }
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldFreeEnabled = ClientConfig.ENABLE_FREELOOK.get(); oldKeep = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        freeKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null); oldFreeKey = freeKey.isDown();
        oldShift = mc.options.keyShift.isDown(); oldUse = mc.options.keyUse.isDown(); oldPause = mc.options.pauseOnLostFocus; oldType = mc.options.getCameraType();
        log = output; index = phase = age = checks = failures = limited = restorePhase = 0;
        restoring = motionStarted = movingStarted = abortAfterDrain = priorityFrozen = motionError = motionStopSeen = false;
        motionRestoreError = Double.NaN; snapshot = null; localAim = null;
        serverShip = clientShip = null; pending = null; detail = "";
        if (!registered) { NeoForge.EVENT_BUS.addListener(SableAddonInteractionProbe::tick); NeoForge.EVENT_BUS.addListener(SableAddonInteractionProbe::frame); registered = true; }
        running = true; mc.setScreen(null); mc.options.pauseOnLostFocus = false; mc.options.keyUse.setDown(false);
        log.accept("SABLE_ADDON_ACTION START operations=18 priorityControls=4 modes=first_person,offset,real_first_person_freelook"
                + " motion=bounded-kinematic existingShip=true airOnlyFixture=true shipScaleUnchanged=true");
        submit(mc, p -> {
            QaPacketBarrier.install(mc.getSingleplayerServer());
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(), p.getAbilities().flying, p.getInventory().selected, inventory);
            originals = new HashSet<>(); for (Entity e : snapshot.level.getAllEntities()) if (e != null) originals.add(e.getUUID());
            findAirFixture(snapshot.level);
            for (BlockPos pos : new BlockPos[]{plotTarget, plotTarget.below()}) {
                if (!snapshot.level.getBlockState(pos).isAir() || snapshot.level.getBlockEntity(pos) != null)
                    throw new IllegalStateException("Refusing occupied plot fixture " + pos);
                savedBlocks.put(pos, snapshot.level.getBlockState(pos));
            }
            initialWorld = world(serverShip, plotTarget.getCenter(), false);
            fixturePosition = initialWorld.add(0, .1, -3.2);
            if (!snapshot.level.getEntities(p, new AABB(plotTarget).inflate(2)).isEmpty()
                    || !snapshot.level.getEntities(p, new AABB(initialWorld.subtract(2, 2, 2), initialWorld.add(2, 2, 2))).isEmpty())
                throw new IllegalStateException("Existing entity at plot target fixture");
            if (!snapshot.level.getEntities(p, new AABB(fixturePosition.subtract(1, 1, 1), fixturePosition.add(1, 3, 1))).isEmpty())
                throw new IllegalStateException("Existing entity at observer fixture");
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities();
            p.teleportTo(snapshot.level, fixturePosition.x, fixturePosition.y, fixturePosition.z, 90, 0);
            log.accept("SABLE_ADDON_ACTION FIXTURE ship=" + shipId + " plot=" + plotTarget + " world=" + initialWorld);
        });
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            age++;
            if (!restoring && localAim != null && clientShip != null && phase >= 3 && phase <= 5) pose(mc);
            if (pending != null) {
                if (!pending.isDone()) {
                    if (age > 1200 && !restoring) abortAfterDrain = true;
                    if (age % 200 == 0) log.accept("SABLE_ADDON_ACTION DRAIN_PENDING phase=" + phase + " actualCallbackCompletionRequired=true");
                    return;
                }
                pending.join(); pending = null;
            }
            if (abortAfterDrain && !restoring) { beginRestore(mc, "ABORT callback-timeout"); return; }
            if (restoring) { restoreTick(mc); return; }
            if (age > 1200) throw new IllegalStateException("Fixture timeout phase=" + phase + " case=" + label());
            if (phase == 0) {
                if (mc.player.position().distanceToSqr(fixturePosition) > .04) return;
                clientShip = ship(mc.level, shipId); if (clientShip == null) return;
                SableMotionProbe.start(mc, line -> {
                    if (line.startsWith("SABLE_MOTION ERROR")) motionError = true;
                    if (line.startsWith("SABLE_MOTION STOP")) {
                        try {
                            Object context = field(SableMotionProbe.class, "context").get(null);
                            Object pose = serverShip.getClass().getMethod("logicalPose").invoke(serverShip);
                            Vector3dc scale = (Vector3dc) pose.getClass().getMethod("scale").invoke(pose);
                            Vector3d expected = new Vector3d(plotTarget.getCenter().x, plotTarget.getCenter().y, plotTarget.getCenter().z)
                                    .sub((Vector3dc) field(context.getClass(), "originalPivot").get(context)).mul(scale);
                            ((Quaterniond) field(context.getClass(), "originalOrientation").get(context)).transform(expected);
                            expected.add((Vector3dc) field(context.getClass(), "originalPosition").get(context));
                            Vec3 actual = world(serverShip, plotTarget.getCenter(), false);
                            motionRestoreError = actual.distanceTo(new Vec3(expected.x, expected.y, expected.z));
                            motionStopSeen = true; if (motionRestoreError > .001) motionError = true;
                        } catch (Exception error) { motionError = true; log.accept("SABLE_ADDON_ACTION MOTION_RESTORE_CHECK_ERROR " + error); }
                    }
                    log.accept("SABLE_ADDON_ACTION MOTION " + line);
                }, false);
                motionStarted = true; phase = 1; age = 0; return;
            }
            if (phase == 1) {
                Object context = field(SableMotionProbe.class, "context").get(null); if (context == null) return;
                if (!shipId.equals(field(context.getClass(), "id").get(context))) throw new IllegalStateException("Motion selected a different ship");
                submit(mc, p -> {
                    field(context.getClass(), "settling").setInt(context, 1_000_000);
                    ((Vector3d) field(context.getClass(), "destination").get(context)).set((Vector3dc) field(context.getClass(), "originalPosition").get(context));
                    ((Quaterniond) field(context.getClass(), "destinationOrientation").get(context)).set((Quaterniond) field(context.getClass(), "originalOrientation").get(context));
                });
                phase = 2; age = 0; return;
            }
            if (phase == 2) {
                if (index == 9 && !movingStarted) {
                    movingStarted = true;
                    submit(mc, p -> {
                        Object context = field(SableMotionProbe.class, "context").get(null);
                        field(context.getClass(), "settling").setInt(context, 1);
                    });
                    phase = 10; age = 0; return;
                }
                if (index >= 22) { beginRestore(mc, "DONE"); return; }
                if (index == 18 && !priorityFrozen) {
                    priorityFrozen = true;
                    submit(mc, p -> {
                        Object context = field(SableMotionProbe.class, "context").get(null);
                        field(context.getClass(), "settling").setInt(context, 1_000_000);
                    });
                    return;
                }
                prepare(mc); return;
            }
            if (phase == 10) {
                double moved = world(clientShip, plotTarget.getCenter(), true).distanceTo(initialWorld);
                if (moved <= .02) return;
                log.accept("SABLE_ADDON_ACTION MOVING_NETWORK_POSE displacement=" + moved);
                phase = 2; age = 0; return;
            }
            pose(mc);
            if (phase == 3) {
                if (age < 12 || !clientReady(mc)) return;
                mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(bodyYaw, bodyPitch, mc.player.onGround()));
                poseSettled = false;
                ordered(mc, p -> { posePlayerTick = p.tickCount; });
                phase = 4; age = 0; return;
            }
            if (phase == 4) {
                if (!poseSettled) {
                    ordered(mc, p -> {
                        poseSettled = p.tickCount >= posePlayerTick + 3;
                        serverReady = p.connection != null && field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(p.connection) == null
                                && p.canInteractWithBlock(plotTarget, 0) && !p.getMainHandItem().isEmpty();
                        detail = "serverYaw=" + p.getYRot() + " serverHead=" + p.getYHeadRot() + " playerTicks=" + (p.tickCount - posePlayerTick);
                    });
                    return;
                }
                if (!serverReady) throw new IllegalStateException("Unready server precondition " + detail);
                if (priority()) { priorityCheck(mc); return; }
                HitResult hit = mc.hitResult;
                if (!(hit instanceof BlockHitResult h) || hit.getType() != HitResult.Type.BLOCK || !h.getBlockPos().equals(plotTarget)) {
                    finishCase(false, "clientTarget=" + hit + " requestSent=false"); phase = 2; age = 0; return;
                }
                Vec3 exact = world(clientShip, hit.getLocation(), false);
                Method bridge = Class.forName("com.caleb.unlockedcamera.client.SableCompat").getDeclaredMethod("interactionWorldPoint", Level.class, Vec3.class);
                bridge.setAccessible(true);
                Vec3 actual = (Vec3) bridge.invoke(null, mc.level, hit.getLocation());
                double bridgeError = actual == null ? Double.POSITIVE_INFINITY : actual.distanceTo(exact);
                if (bridgeError > 1.0E-6) throw new IllegalStateException("Actual plot point bridge mismatch " + bridgeError);
                log.accept("SABLE_ADDON_ACTION BEFORE " + label() + " plot=" + h.getBlockPos() + " worldPoint=" + actual
                        + " bridgeError=" + bridgeError + " body=" + bodyYaw + "," + bodyPitch
                        + " held=" + Arrays.toString(UnlockedCameraClient.continuousAimAngles()) + " " + detail);
                clientResult = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, h).toString();
                ordered(mc, p -> {
                    if (wire()) {
                        Object component = p.getMainHandItem().get(connectionType);
                        Object endpoint = component == null ? null : component.getClass().getMethod("endpoint").invoke(component);
                        serverEffect = endpoint != null && plotTarget.equals(endpoint.getClass().getMethod("getPos").invoke(endpoint));
                        detail = "endpoint=" + endpoint;
                    } else {
                        Object console = snapshot.level.getBlockEntity(plotTarget);
                        Object module = consoleClass.getMethod("module", int.class).invoke(console, socket);
                        int returned = 0; for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(item(MODULE).getItem())) returned += p.getInventory().getItem(i).getCount();
                        serverEffect = remove() ? module == null && returned == 1 : module != null
                                && ((ItemStack) module.getClass().getMethod("toStack").invoke(module)).is(item(MODULE).getItem());
                        detail = "modulePresent=" + (module != null) + " returned=" + returned;
                    }
                });
                phase = 5; age = 0; return;
            }
            if (phase == 5) {
                boolean accepted = !clientResult.equals("FAIL") && !clientResult.equals("PASS");
                finishCase(serverEffect && (!wire() || accepted), "clientResult=" + clientResult + " serverEffect=" + serverEffect + " " + detail);
                phase = 2; age = 0;
            }
        } catch (Throwable error) {
            log.accept("SABLE_ADDON_ACTION ERROR " + error);
            try {
                if (restoring) { running = false; log.accept("SABLE_ADDON_ACTION NOT_RESTORED error=" + error); }
                else beginRestore(mc, "ABORT");
            } catch (Throwable failure) { running = false; log.accept("SABLE_ADDON_ACTION NOT_RESTORED " + failure); }
        }
    }

    private static void prepare(Minecraft mc) throws Exception {
        localAim = null; mc.options.keyUse.setDown(false); mc.player.stopUsingItem(); freeKey.setDown(false);
        field(UnlockedCameraClient.class, "active").setBoolean(null, false); dropAim();
        submit(mc, p -> {
            p.stopUsingItem();
            snapshot.level.setBlock(plotTarget, Blocks.AIR.defaultBlockState(), 3);
            snapshot.level.setBlock(plotTarget.below(), Blocks.STONE.defaultBlockState(), 3);
            snapshot.level.setBlock(plotTarget, BuiltInRegistries.BLOCK.get(ResourceLocation.parse(wire() ? CONNECTOR : CONSOLE)).defaultBlockState(), 3);
            if (wire()) {
                BlockState state = snapshot.level.getBlockState(plotTarget);
                Object terminal = Class.forName("org.patryk3211.powergrid.electricity.base.IElectric")
                        .getMethod("terminal", BlockState.class, int.class).invoke(state.getBlock(), state, 0);
                localAim = ((AABB) terminal.getClass().getMethod("getOutline").invoke(terminal)).getCenter().add(Vec3.atLowerCornerOf(plotTarget));
            } else {
                Object console = snapshot.level.getBlockEntity(plotTarget);
                List<?> mounts = (List<?>) consoleClass.getMethod("freeMounts", moduleTypeClass).invoke(console, moduleType);
                Object root = null;
                for (Object spot : mounts) {
                    Object target = spot.getClass().getMethod("target").invoke(spot);
                    if (target.getClass().getMethod("subPath").invoke(target) == null) { root = spot; socket = (int) target.getClass().getMethod("socket").invoke(target); break; }
                }
                if (root == null) throw new IllegalStateException("No root button socket");
                localAim = ((Vec3) root.getClass().getMethod("center").invoke(root)).add(Vec3.atLowerCornerOf(plotTarget));
                if (remove() && !(boolean) consoleClass.getMethod("mount", int.class, ItemStack.class).invoke(console, socket, item(MODULE)))
                    throw new IllegalStateException("Cannot seed removal fixture");
            }
            p.getInventory().clearContent(); p.getInventory().selected = 0;
            ItemStack main = item(wire() ? WIRE : remove() ? "create:wrench" : MODULE);
            if (index == 19) main = new ItemStack(Items.BOW);
            main.remove(connectionType); p.getInventory().setItem(0, main);
            if (index == 19) p.setItemInHand(InteractionHand.OFF_HAND, item(MODULE));
            if (index >= 20) p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BOW));
            p.setShiftKeyDown(false); p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
        });
        phase = 3; age = 0;
    }

    private static boolean clientReady(Minecraft mc) throws Exception {
        String expected = wire() ? CONNECTOR : CONSOLE;
        if (!mc.level.getBlockState(plotTarget).is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(expected)))) return false;
        String main = index == 19 ? "minecraft:bow" : wire() ? WIRE : remove() ? "create:wrench" : MODULE;
        if (!mc.player.getMainHandItem().is(item(main).getItem()) || mc.player.getMainHandItem().has(connectionType)) return false;
        if (index == 19 && !mc.player.getOffhandItem().is(item(MODULE).getItem())) return false;
        if (index >= 20 && !mc.player.getOffhandItem().is(Items.BOW)) return false;
        if (!priority() && !mc.player.getOffhandItem().isEmpty()) return false;
        if (!wire()) {
            Object console = mc.level.getBlockEntity(plotTarget); if (!consoleClass.isInstance(console)) return false;
            Object module = consoleClass.getMethod("module", int.class).invoke(console, socket);
            if ((module != null) != remove()) return false;
        }
        if (index >= 20 && !mc.player.isUsingItem()) {
            // This starts an ordinary bow draw. Cleanup calls stopUsingItem;
            // no release packet or projectile-firing method is invoked.
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            // Vanilla releases an active draw before the next Post event if
            // this key is up. Hold only after our explicit offhand use started.
            mc.options.keyUse.setDown(mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND);
            return false;
        }
        return true;
    }

    private static void priorityCheck(Minecraft mc) throws Exception {
        float[] interaction = AddonInteractionAim.angles(mc), projectile = UnlockedCameraClient.crosshairAimAngles(), held = UnlockedCameraClient.continuousAimAngles();
        boolean shouldInteract = index == 18;
        boolean actualUsing = mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND;
        boolean pass = shouldInteract ? interaction != null && close(held, interaction)
                : close(held, projectile) && (index == 19 ? interaction == null : actualUsing && interaction != null);
        checks++; if (!pass) failures++;
        log.accept("SABLE_ADDON_PRIORITY " + (pass ? "PASS" : "FAIL") + " " + label()
                + " activeOffhand=" + actualUsing + " interaction=" + Arrays.toString(interaction)
                + " projectile=" + Arrays.toString(projectile) + " held=" + Arrays.toString(held)
                + " scope=cache-priority-no-weapon-release");
        mc.options.keyUse.setDown(false); mc.player.stopUsingItem(); ordered(mc, ServerPlayer::stopUsingItem);
        index++; phase = 2; age = 0;
    }

    private static void pose(Minecraft mc) throws Exception {
        if (localAim == null || restoring) return;
        mc.player.getInventory().selected = 0; mc.options.keyShift.setDown(false); mc.player.setShiftKeyDown(false); mc.player.input.shiftKeyDown = false;
        boolean offset = mode() == 1, free = mode() == 2;
        ClientConfig.TOGGLE_MOD.set(mode() != 0); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        field(UnlockedCameraClient.class, "active").setBoolean(null, offset);
        field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4); field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
        field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
        mc.options.setCameraType(offset ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON); freeKey.setDown(free);
        Vec3 target = world(clientShip, localAim, true), origin = offset ? mc.player.getEyePosition().add(.95, 0, -4) : mc.player.getEyePosition();
        float[] aim = angles(target.subtract(origin)); bodyYaw = mode() == 0 ? aim[0] : aim[0] + 90; bodyPitch = mode() == 0 ? aim[1] : 0;
        body(mc, bodyYaw, bodyPitch);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, free ? aim[0] - bodyYaw : 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, free ? aim[1] - bodyPitch : 0);
        setCameraPosition.invoke(mc.gameRenderer.getMainCamera(), origin); setCameraRotation.invoke(mc.gameRenderer.getMainCamera(), aim[0], aim[1], 0f);
        mc.gameRenderer.pick(1);
    }

    private static void frame(RenderFrameEvent.Post ignored) {
        if (!running || restoring || clientShip == null || phase < 3 || phase > 5) return;
        try { pose(Minecraft.getInstance()); } catch (Exception e) { log.accept("SABLE_ADDON_ACTION CAMERA_ERROR " + e); }
    }

    private static void finishCase(boolean effect, String observation) {
        String key = (index < 9 ? "stationary" : "moving") + "/" + operation();
        if (mode() == 0) controls.put(key, effect);
        boolean positive = controls.getOrDefault(key, false);
        String status = mode() != 0 && !positive ? "INCONCLUSIVE" : effect ? "PASS" : "FAIL";
        if (status.equals("INCONCLUSIVE")) limited++; else { checks++; if (!effect) failures++; }
        log.accept("SABLE_ADDON_ACTION " + status + " " + label() + " positiveControl=" + positive + " " + observation);
        index++;
    }

    private static void beginRestore(Minecraft mc, String reason) throws Exception {
        if (pending != null && !pending.isDone()) { abortAfterDrain = true; return; }
        restoring = true; finishReason = reason; restorePhase = 0; age = 0; localAim = null;
        mc.options.keyUse.setDown(false); mc.player.stopUsingItem(); dropAim();
        for (var saved : savedCamera.entrySet()) saved.getKey().set(null, saved.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder); ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        ClientConfig.ENABLE_FREELOOK.set(oldFreeEnabled); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeep); freeKey.setDown(oldFreeKey);
        mc.options.keyShift.setDown(oldShift); mc.player.setShiftKeyDown(oldShift); mc.player.input.shiftKeyDown = oldShift;
        mc.options.keyUse.setDown(oldUse);
        mc.options.setCameraType(oldType); mc.options.pauseOnLostFocus = oldPause;
        if (snapshot == null) { running = false; log.accept("SABLE_ADDON_ACTION RESTORED stateUntouched=true reason=" + reason); return; }
        ordered(mc, p -> {
            p.stopUsingItem();
            // Restore mass-changing fixture cells before the motion owner restores its pivot/velocity.
            for (var saved : savedBlocks.entrySet()) snapshot.level.setBlock(saved.getKey(), saved.getValue(), 3);
            List<Entity> remove = new ArrayList<>();
            for (Entity e : snapshot.level.getAllEntities()) if (e != null && !originals.contains(e.getUUID())
                    && ((initialWorld != null && e.position().distanceToSqr(initialWorld) < 100)
                    || (plotTarget != null && e.position().distanceToSqr(plotTarget.getCenter()) < 100))) remove.add(e);
            for (Entity e : remove) e.discard();
        });
        log.accept("SABLE_ADDON_ACTION " + reason + " checks=" + checks + " failures=" + failures + " limited=" + limited + " restoration=pending");
    }

    private static void restoreTick(Minecraft mc) throws Exception {
        if (age > 2400) throw new IllegalStateException("Restoration did not converge");
        if (restorePhase == 0) { if (motionStarted) SableMotionProbe.stop(mc); restorePhase = 1; return; }
        if (restorePhase == 1) {
            if (field(SableMotionProbe.class, "context").get(null) != null || field(SableMotionProbe.class, "requested").getBoolean(null)) return;
            ordered(mc, p -> {
                p.getInventory().clearContent(); for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i).copy());
                p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
                p.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
            });
            restorePhase = 2; return;
        }
        body(mc, snapshot.yaw, snapshot.pitch); mc.player.getInventory().selected = snapshot.selected;
        if (mc.player.position().distanceToSqr(snapshot.position) > .0004 || !inventoryMatches(mc.player.getInventory())) return;
        if (restorePhase == 2) {
            ordered(mc, p -> {
                stateRestored = inventoryMatches(p.getInventory()) && p.getInventory().selected == snapshot.selected
                        && p.position().distanceToSqr(snapshot.position) < .0004 && p.gameMode.getGameModeForPlayer() == snapshot.mode
                        && p.getAbilities().flying == snapshot.flying && Math.abs(Mth.wrapDegrees(p.getYRot() - snapshot.yaw)) < .05
                        && Math.abs(p.getXRot() - snapshot.pitch) < .05 && field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(p.connection) == null;
                for (var saved : savedBlocks.entrySet()) stateRestored &= snapshot.level.getBlockState(saved.getKey()).equals(saved.getValue()) && snapshot.level.getBlockEntity(saved.getKey()) == null;
            });
            restorePhase = 3; return;
        }
        if (!stateRestored) { restorePhase = 2; return; }
        running = false;
        boolean motionRestored = !motionStarted || motionStopSeen && !motionError;
        log.accept("SABLE_ADDON_ACTION " + (motionRestored ? "RESTORED" : "NOT_RESTORED") + " checks=" + checks + " failures=" + failures + " limited=" + limited
                + " stateRestored=true poseRestored=true motionOwnerReleased=true motionRestored=" + motionRestored
                + " shipPointRestoreError=" + motionRestoreError + " reason=" + finishReason);
    }

    private static void findAirFixture(ServerLevel level) throws Exception {
        Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        Object all = container.getMethod("getContainer", Level.class).invoke(null, level);
        for (Object candidate : (List<?>) container.getMethod("getAllSubLevels").invoke(all)) {
            Object plot = candidate.getClass().getMethod("getPlot").invoke(candidate);
            for (Object holder : (Collection<?>) plot.getClass().getMethod("getLoadedChunks").invoke(plot)) {
                LevelChunk chunk = (LevelChunk) holder.getClass().getMethod("getChunk").invoke(holder);
                for (int section = 0; section < chunk.getSections().length; section++) {
                    var data = chunk.getSection(section); if (data.hasOnlyAir()) continue;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        String id = BuiltInRegistries.BLOCK.getKey(data.getBlockState(x, y, z).getBlock()).toString();
                        if (!id.startsWith("create:") || !id.endsWith("_seat")) continue;
                        BlockPos target = new BlockPos(chunk.getPos().getMinBlockX() + x, level.getSectionYFromSectionIndex(section) * 16 + y + 4, chunk.getPos().getMinBlockZ() + z);
                        boolean clear = true;
                        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-1, -1, -1), target.offset(1, 2, 1)))
                            if (!level.getBlockState(pos).isAir() || level.getBlockEntity(pos) != null) clear = false;
                        if (!clear) continue;
                        serverShip = candidate; shipId = (UUID) candidate.getClass().getMethod("getUniqueId").invoke(candidate); plotTarget = target; return;
                    }
                }
            }
        }
        throw new IllegalStateException("No loaded seat ship with empty fixture cells above it");
    }

    private static Object ship(Level level, UUID id) throws Exception {
        Class<?> type = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        return type.getMethod("getSubLevel", UUID.class).invoke(type.getMethod("getContainer", Level.class).invoke(null, level), id);
    }
    private static Vec3 world(Object ship, Vec3 plot, boolean rendered) throws Exception {
        Object pose = rendered ? ship.getClass().getMethod("renderPose", float.class).invoke(ship, 1f) : ship.getClass().getMethod("logicalPose").invoke(ship);
        return (Vec3) transform.invoke(pose, plot);
    }
    private static int mode() { return priority() ? 1 : index % 3; }
    private static boolean priority() { return index >= 18; }
    private static boolean wire() { return index == 21 || !priority() && (index / 3) % 3 == 2; }
    private static boolean remove() { return !priority() && (index / 3) % 3 == 1; }
    private static String operation() { return wire() ? "wire_endpoint" : remove() ? "module_remove" : "module_attach"; }
    private static String label() {
        if (priority()) return new String[]{"main_module_idle", "main_bow_offhand_module", "main_module_active_offhand_bow", "main_wire_active_offhand_bow"}[Math.min(index - 18, 3)];
        return (index < 9 ? "stationary" : "moving") + " " + operation() + " " + MODES[mode()];
    }
    private static boolean close(float[] a, float[] b) { return a != null && b != null && Math.abs(Mth.wrapDegrees(a[0] - b[0])) < .1 && Math.abs(a[1] - b[1]) < .1; }
    private static float[] angles(Vec3 d) { return new float[]{(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance()))}; }
    private static ItemStack item(String id) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))); }
    private static Field field(Class<?> type, String name) throws Exception { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
    private static void dropAim() throws Exception { Method m = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); m.setAccessible(true); m.invoke(null); }
    private static boolean inventoryMatches(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 0; i < snapshot.inventory.size(); i++) if (!ItemStack.matches(inventory.getItem(i), snapshot.inventory.get(i))) return false;
        return true;
    }
    private static void body(Minecraft mc, float yaw, float pitch) throws Exception {
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setYHeadRot(yaw); mc.player.yHeadRotO = yaw;
        mc.player.setYBodyRot(yaw); mc.player.yBodyRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager); if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation"); Object value = rotation.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, value); field(camera.getClass(), "playerRotation").set(camera, value);
    }
    private static void ordered(Minecraft mc, ServerAction action) {
        pending = QaPacketBarrier.send(mc, p -> { try { action.run(p); } catch (Exception e) { throw new IllegalStateException(e); } });
    }
    private static void submit(Minecraft mc, ServerAction action) {
        CompletableFuture<Void> done = new CompletableFuture<>(); pending = done; UUID id = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            Throwable failure = null;
            try { action.run(mc.getSingleplayerServer().getPlayerList().getPlayer(id)); } catch (Throwable e) { failure = e; }
            finally { if (failure == null) done.complete(null); else done.completeExceptionally(failure); }
        });
    }
}
