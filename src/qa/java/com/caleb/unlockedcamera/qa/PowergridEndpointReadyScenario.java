package com.caleb.unlockedcamera.qa;

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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Paired real wire first-endpoint requests, with first-person positive controls. */
public final class PowergridEndpointReadyScenario {
    private static final Vec3 POSITION = new Vec3(1710.5, 210, 190.5);
    private static final BlockPos TARGET = new BlockPos(1711, 211, 193);
    private static final Map<BlockPos, BlockState> savedBlocks = new LinkedHashMap<>();
    private static final Map<Field, Object> savedCamera = new LinkedHashMap<>();
    private static final ArrayDeque<Step> steps = new ArrayDeque<>();
    private static final List<CompletableFuture<Void>> serverCallbacks = new ArrayList<>();
    private static boolean registered, running, restoring, fixtureInitialized;
    private static boolean restorationRequested;
    private static String finishStatus;
    private static int wait, failures, checks, readinessTicks, pendingTicks;
    private static float bodyYaw, bodyPitch, expectedServerYaw, expectedServerPitch;
    private static boolean shoulder, freelookCandidates, currentCandidate, oldFreelook, oldFreelookEnabled, oldKeepDirection;
    private static boolean regression;
    private static volatile int packetPosePlayerTick;
    private static volatile boolean packetPoseSettled;
    private static KeyMapping freelookKey;
    private static boolean clientAccepted, serverFresh, clientFresh;
    private static Consumer<String> log;
    private static CompletableFuture<?> pending;
    private static Snapshot snapshot;
    private static CameraType oldType;
    private static boolean oldToggle, oldShoulder, oldShift;
    private static double oldAmount, oldMax;
    private static Vec3 terminalPoint;
    private static DataComponentType<?> connectionType;
    private static final Map<String, Boolean> baselines = new LinkedHashMap<>();
    private record Step(int delay, Action action) {}
    @FunctionalInterface private interface Action { void run(Minecraft mc) throws Exception; }
    private record Snapshot(Vec3 position, float yaw, float pitch, GameType mode, int selected, List<ItemStack> inventory) {}
    private PowergridEndpointReadyScenario() {}

    /** Direct dynamic entry point for the corrected offset fixture. */
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        run(mc, output, false);
    }

    public static void run(Minecraft mc, Consumer<String> output, boolean useFreelook) throws Exception {
        runInternal(mc, output, useFreelook, false);
    }

    public static void runRegression(Minecraft mc, Consumer<String> output, boolean useFreelook) throws Exception {
        runInternal(mc, output, useFreelook, true);
    }

    private static void runInternal(Minecraft mc, Consumer<String> output, boolean useFreelook, boolean verifyFix) throws Exception {
        if (running) throw new IllegalStateException("Powergrid probe already running");
        if (mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("Integrated server required");
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(PowergridEndpointReadyScenario::tick);
            NeoForge.EVENT_BUS.addListener(PowergridEndpointReadyScenario::frame);
            registered = true;
        }
        freelookCandidates = useFreelook; currentCandidate = false; regression = verifyFix;
        String marker = regression ? (useFreelook ? "POWERGRID_FREELOOK_REGRESSION" : "POWERGRID_REGRESSION")
                : useFreelook ? "POWERGRID_FREELOOK" : "POWERGRID";
        log = message -> output.accept(message.replace("POWERGRID", marker));
        checks = failures = wait = readinessTicks = pendingTicks = 0; bodyYaw = bodyPitch = 0;
        restoring = restorationRequested = fixtureInitialized = shoulder = false;
        pending = null; snapshot = null; terminalPoint = null; serverCallbacks.clear();
        steps.clear(); savedBlocks.clear(); savedCamera.clear(); baselines.clear();
        oldType = mc.options.getCameraType(); oldToggle = ClientConfig.TOGGLE_MOD.get();
        oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get(); oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get();
        oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get(); oldShift = mc.options.keyShift.isDown();
        freelookKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        oldFreelook = freelookKey.isDown(); oldFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get();
        oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "shoulderSide", "freelookYaw", "freelookPitch"}) {
            Field f = field(UnlockedCameraClient.class, name); savedCamera.put(f, f.get(null));
        }
        Object supplier = Class.forName("org.patryk3211.powergrid.collections.ModdedDataComponents").getField("CONNECTION_DATA").get(null);
        connectionType = (DataComponentType<?>) Class.forName("dev.architectury.registry.registries.RegistrySupplier").getMethod("get").invoke(supplier);
        running = true; log.accept("POWERGRID START first-endpoint only; baseline=first-person-disabled candidate="
                + (freelookCandidates ? "first_person_freelook_key_held" : "shoulder")
                + " keepDirection=" + (freelookCandidates ? false : oldKeepDirection));
        add(35, m -> server(m, p -> {
            QaPacketBarrier.install(m.getSingleplayerServer());
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(), p.getInventory().selected, inventory);
            for (BlockPos pos : BlockPos.betweenClosed(1709, 209, 189, 1713, 213, 195)) {
                if (p.serverLevel().getBlockEntity(pos) != null) throw new IllegalStateException("Existing fixture block entity " + pos);
                savedBlocks.put(pos.immutable(), p.serverLevel().getBlockState(pos));
            }
            fixtureInitialized = true;
            for (BlockPos pos : savedBlocks.keySet()) p.serverLevel().setBlock(pos,
                    pos.getY() == 209 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            p.setGameMode(GameType.CREATIVE); p.getInventory().clearContent(); p.getInventory().selected = 0;
            p.teleportTo(p.serverLevel(), POSITION.x, POSITION.y, POSITION.z, 0, 0);
            p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
        }));
        for (String item : new String[]{"powergrid:wire", "powergrid:iron_wire", "powergrid:golden_wire",
                "powergrid:insulated_copper_wire", "powergrid:copper_cord"}) {
            // Ordinary terminals deliberately reject iron: their LIGHT_WIRES tag
            // contains copper/gold/insulated copper only. Heavy terminals accept it.
            String connector = item.equals("powergrid:iron_wire")
                    ? "powergrid:heavy_wire_connector" : "powergrid:wire_connector";
            addCase(item, connector, false);
            addCase(item, connector, true);
        }
        add(0, m -> finish(m, "DONE"));
    }

    private static void addCase(String item, String block, boolean useShoulder) {
        String label = item + (useShoulder ? (freelookCandidates ? " freelook" : " shoulder") : " first-person");
        add(15, m -> {
            currentCandidate = useShoulder; shoulder = useShoulder && !freelookCandidates; bodyYaw = bodyPitch = 0;
            readinessTicks = 0; terminalPoint = null;
            clientAccepted = serverFresh = clientFresh = false;
            m.options.keyShift.setDown(false); m.player.setShiftKeyDown(false); m.player.input.shiftKeyDown = false;
            m.setScreen(null); m.player.getInventory().selected = 0;
            ClientConfig.TOGGLE_MOD.set(useShoulder); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field(UnlockedCameraClient.class, "active").setBoolean(null, shoulder);
            field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4);
            field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
            field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
            field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, 0);
            field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, 0);
            ClientConfig.ENABLE_FREELOOK.set(true); freelookKey.setDown(useShoulder && freelookCandidates);
            if (freelookCandidates) ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
            m.options.setCameraType(shoulder ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
            server(m, p -> {
                p.serverLevel().setBlock(TARGET, Blocks.AIR.defaultBlockState(), 3);
                p.serverLevel().setBlock(TARGET.below(), Blocks.STONE.defaultBlockState(), 3);
                p.serverLevel().setBlock(TARGET, BuiltInRegistries.BLOCK.get(ResourceLocation.parse(block)).defaultBlockState(), 3);
                ItemStack fresh = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)), 64);
                fresh.remove(connectionType);
                if (fresh.has(connectionType)) throw new IllegalStateException("Fresh wire unexpectedly has endpoint");
                p.getInventory().setItem(0, fresh);
                p.getInventory().selected = 0; p.getInventory().offhand.set(0, ItemStack.EMPTY);
                p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
            });
        });
        add(0, m -> awaitClientFixture(m, item, block, label));
        add(20, m -> {
            BlockState state = m.level.getBlockState(TARGET);
            Class<?> electric = Class.forName("org.patryk3211.powergrid.electricity.base.IElectric");
            // CordItem has its own endpoint path; solid wires must pass IElectric.accepts.
            boolean terminalAccepts = item.equals("powergrid:copper_cord")
                    || (boolean) electric.getMethod("accepts", ItemStack.class).invoke(state.getBlock(), m.player.getMainHandItem());
            if (!terminalAccepts) throw new IllegalStateException("Powergrid terminal rejects fixture wire " + item + " at " + state);
            Object terminal = electric.getMethod("terminal", BlockState.class, int.class).invoke(state.getBlock(), state, 0);
            AABB outline = (AABB) terminal.getClass().getMethod("getOutline").invoke(terminal);
            terminalPoint = outline.getCenter().add(Vec3.atLowerCornerOf(TARGET));
            readinessTicks = 0;
            if (!useShoulder) {
                float[] angles = angles(terminalPoint.subtract(m.player.getEyePosition()));
                bodyYaw = angles[0]; bodyPitch = angles[1];
            } else if (freelookCandidates) {
                float[] aim = angles(terminalPoint.subtract(m.player.getEyePosition()));
                field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, aim[0]);
                field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, aim[1]);
            }
            log.accept("POWERGRID FIXTURE " + label + " state=" + state + " terminal=" + terminalPoint
                    + " compatibleEndpointType=" + terminalAccepts);
            applyFixturePose(m);
        });
        add(6, PowergridEndpointReadyScenario::awaitHeldAim);
        add(0, m -> {
            log.accept("POWERGRID CLIENT_PRE_BARRIER " + label + " heldAim="
                    + java.util.Arrays.toString(UnlockedCameraClient.continuousAimAngles())
                    + " hit=" + (m.hitResult == null ? "null" : describe(m.hitResult))
                    + " body=" + m.player.getYRot() + "," + m.player.getXRot());
            ordered(m, p -> {
            packetPosePlayerTick = p.tickCount; packetPoseSettled = false;
            serverFresh = !p.getMainHandItem().has(connectionType);
            boolean synced = Math.abs(p.getYRot() - expectedServerYaw) < .02 && Math.abs(p.getXRot() - expectedServerPitch) < .02;
            log.accept("POWERGRID BEFORE " + label + " serverFresh=" + serverFresh + " serverYaw=" + p.getYRot()
                    + " serverPitch=" + p.getXRot() + " serverAimSynced=" + synced
                    + " expectedServerAim=" + expectedServerYaw + "," + expectedServerPitch
                    + " freelookKey=" + freelookKey.isDown());
            if (!serverFresh || !synced) throw new IllegalStateException("Powergrid fixture has stale endpoint or rotation");
            });
        });
        if (regression) add(0, PowergridEndpointReadyScenario::awaitServerPoseTicks);
        add(12, m -> {
            Camera camera = m.gameRenderer.getMainCamera();
            Vec3 eye = m.player.getEyePosition();
            Vec3 origin = shoulder ? eye.add(.95, 0, -4) : eye;
            Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true); position.invoke(camera, origin);
            float[] angles = angles(terminalPoint.subtract(origin));
            if (useShoulder && freelookCandidates) {
                field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, angles[0]);
                field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, angles[1]);
            }
            Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
            rotation.setAccessible(true); rotation.invoke(camera, angles[0], angles[1], 0f);
            m.gameRenderer.pick(1);
            if (!(m.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(TARGET)) {
                throw new IllegalStateException("Fixture unpickable " + label + " hit=" + m.hitResult);
            }
            HitResult repick = m.player.pick(6, 0, false);
            Object electric = m.level.getBlockState(TARGET).getBlock();
            int index = (int) Class.forName("org.patryk3211.powergrid.electricity.base.IElectric")
                    .getMethod("terminalIndexAt", BlockState.class, Vec3.class).invoke(electric,
                            m.level.getBlockState(TARGET), hit.getLocation().subtract(Vec3.atLowerCornerOf(TARGET)));
            if (index < 0) throw new IllegalStateException("Camera hits non-terminal " + label + " point=" + hit.getLocation());
            clientFresh = !m.player.getMainHandItem().has(connectionType);
            if (!clientFresh) throw new IllegalStateException("Client endpoint was not cleared before " + label);
            var result = m.gameMode.useItemOn(m.player, InteractionHand.MAIN_HAND, hit);
            clientAccepted = result.consumesAction();
            log.accept("POWERGRID REQUEST " + label + " cameraTarget=" + hit.getBlockPos() + " terminalIndex=" + index
                    + " bodyRepick=" + describe(repick) + " body=" + bodyYaw + "," + bodyPitch
                    + " clientFresh=" + clientFresh + " result=" + result);
        });
        add(0, m -> ordered(m, p -> {
            Object connection = p.getMainHandItem().get(connectionType);
            boolean selected = false;
            String endpoint = "null";
            if (connection != null) {
                try {
                    Object ep = connection.getClass().getMethod(item.equals("powergrid:copper_cord") ? "half" : "endpoint").invoke(connection);
                    endpoint = String.valueOf(ep);
                    if (ep != null) {
                        Object pos = ep.getClass().getMethod("getPos").invoke(ep);
                        selected = TARGET.equals(pos);
                    }
                } catch (NoSuchMethodException e) {
                    // Endpoint exposes its own position via serialize; log full object but do not count mere non-null as pass.
                    throw new IllegalStateException("Endpoint oracle needs exact BlockPos method: " + endpoint, e);
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
            boolean pass = selected && clientAccepted && clientFresh && serverFresh;
            if (!useShoulder) baselines.put(item, pass);
            checks++;
            if (!pass) failures++;
            log.accept("POWERGRID " + (pass ? "PASS " : "FAIL ") + label + " clientAccepted=" + clientAccepted
                    + " serverSelected=" + selected + " serverEndpoint=" + endpoint + " expected=" + TARGET
                    + " positiveControl=" + baselines.get(item) + " serverYaw=" + p.getYRot());
        }));
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (restorationRequested) {
                if (serverCallbacks.stream().anyMatch(callback -> !callback.isDone())) {
                    if (++pendingTicks % 200 == 0) log.accept("POWERGRID DRAIN_PENDING awaitingActualServerCallbackFinally=true");
                    return;
                }
                restorationRequested = false; finish(mc, finishStatus); return;
            }
            if (!restoring && mc.player != null) applyFixturePose(mc);
            if (pending != null) {
                if (!pending.isDone()) {
                    if (++pendingTicks > 1200 && !restoring) throw new IllegalStateException("Powergrid server/packet barrier timed out");
                    if (restoring && pendingTicks % 200 == 0) log.accept("POWERGRID RESTORE_PENDING orderedServerCallback=true");
                    return;
                }
                pending.join(); pending = null; pendingTicks = 0;
            }
            if (restoring) {
                running = false;
                if (snapshot != null) {
                    mc.player.getInventory().selected = snapshot.selected;
                    bodyYaw = snapshot.yaw; bodyPitch = snapshot.pitch;
                    mc.player.setYRot(bodyYaw); mc.player.setXRot(bodyPitch); stabilizeBtp();
                }
                log.accept("POWERGRID RESTORED checks=" + checks + " failures=" + failures); return;
            }
            if (wait-- > 0) return;
            Step step = steps.poll(); if (step == null) { finish(mc, "DONE"); return; }
            step.action.run(mc); wait = step.delay;
        } catch (Throwable e) {
            log.accept("POWERGRID ERROR " + e); e.printStackTrace();
            try { finish(mc, "ABORT"); } catch (Exception restore) { running = false; log.accept("POWERGRID RESTORE_ERROR " + restore); }
        }
    }

    private static void finish(Minecraft mc, String status) throws Exception {
        steps.clear(); terminalPoint = null;
        if (serverCallbacks.stream().anyMatch(callback -> !callback.isDone())) {
            restorationRequested = true; finishStatus = status; pending = null; pendingTicks = 0;
            log.accept("POWERGRID DRAIN_PENDING status=" + status + " awaitingActualServerCallbackFinally=true");
            return;
        }
        if (pending != null && !pending.isDone()) pending.cancel(false);
        pending = null;
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        for (var entry : savedCamera.entrySet()) entry.getKey().set(null, entry.getValue());
        mc.options.setCameraType(oldType); mc.options.keyShift.setDown(oldShift); mc.player.setShiftKeyDown(oldShift);
        freelookKey.setDown(oldFreelook); ClientConfig.ENABLE_FREELOOK.set(oldFreelookEnabled);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        if (snapshot != null) {
            bodyYaw = snapshot.yaw; bodyPitch = snapshot.pitch;
            mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw;
            mc.player.setXRot(bodyPitch); mc.player.xRotO = bodyPitch;
            mc.player.getInventory().selected = snapshot.selected;
            stabilizeBtp();
        }
        Consumer<ServerPlayer> restore = p -> {
            if (fixtureInitialized) for (var entry : savedBlocks.entrySet()) p.serverLevel().setBlock(entry.getKey(), entry.getValue(), 3);
            if (snapshot != null) {
                for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
                p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode);
                p.teleportTo(p.serverLevel(), snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
            }
        };
        if (snapshot == null) server(mc, restore); else ordered(mc, restore);
        restoring = true; log.accept("POWERGRID " + status + " checks=" + checks + " failures=" + failures + " restoration=pending");
    }

    private static float[] angles(Vec3 d) {
        return new float[]{(float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x*d.x + d.z*d.z)))};
    }
    private static String describe(HitResult hit) {
        return hit.getType() + "@" + (hit instanceof BlockHitResult block ? block.getBlockPos() : hit.getLocation());
    }
    private static void frame(RenderFrameEvent.Post event) {
        if (!running || restoring || restorationRequested || terminalPoint == null) return;
        try { applyFixturePose(Minecraft.getInstance()); }
        catch (Exception error) { log.accept("POWERGRID CAMERA_ERROR " + error); }
    }
    private static void applyFixturePose(Minecraft mc) throws Exception {
        mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw;
        mc.player.setXRot(bodyPitch); mc.player.xRotO = bodyPitch;
        freelookKey.setDown(currentCandidate && freelookCandidates);
        stabilizeBtp();
        if (terminalPoint == null) return;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 origin = shoulder ? eye.add(.95, 0, -4) : eye;
        float[] aim = angles(terminalPoint.subtract(origin));
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, currentCandidate && freelookCandidates ? aim[0] - bodyYaw : 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, currentCandidate && freelookCandidates ? aim[1] - bodyPitch : 0);
        Camera camera = mc.gameRenderer.getMainCamera();
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true); position.invoke(camera, origin);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        rotation.setAccessible(true); rotation.invoke(camera, aim[0], aim[1], 0f);
        // The interaction cache consumes hitResult on the next Pre tick. The
        // unfocused test renderer may have picked before this explicit pose.
        if (regression) mc.gameRenderer.pick(1);
    }
    private static void awaitHeldAim(Minecraft mc) throws Exception {
        applyFixturePose(mc);
        if (regression) mc.gameRenderer.pick(1);
        float[] held = UnlockedCameraClient.continuousAimAngles();
        boolean expectHold = currentCandidate && (freelookCandidates || regression);
        Vec3 target = regression && mc.hitResult instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK
                ? h.getLocation() : terminalPoint;
        float[] expected = expectHold
                ? angles(target.subtract(mc.player.getEyePosition())) : new float[]{bodyYaw, bodyPitch};
        boolean ready = expectHold
                ? held != null && Math.abs(net.minecraft.util.Mth.wrapDegrees(held[0] - expected[0])) < .02 && Math.abs(held[1] - expected[1]) < .02
                : held == null;
        if (!ready) {
            if (++readinessTicks > 1200) throw new IllegalStateException("Powergrid fixture aim cache did not settle expected="
                    + expected[0] + "," + expected[1] + " actual=" + java.util.Arrays.toString(held));
            steps.addFirst(new Step(0, PowergridEndpointReadyScenario::awaitHeldAim)); return;
        }
        expectedServerYaw = expected[0]; expectedServerPitch = expected[1];
        log.accept("POWERGRID AIM_READY held=" + java.util.Arrays.toString(held)
                + " expected=" + java.util.Arrays.toString(expected)
                + " bodyPacket=" + bodyYaw + "," + bodyPitch);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(bodyYaw, bodyPitch, mc.player.onGround()));
    }
    private static void awaitServerPoseTicks(Minecraft mc) {
        if (packetPoseSettled) return;
        steps.addFirst(new Step(0, PowergridEndpointReadyScenario::awaitServerPoseTicks));
        ordered(mc, p -> {
            packetPoseSettled = p.tickCount >= packetPosePlayerTick + 3;
            if (packetPoseSettled) log.accept("POWERGRID POSE_SETTLED playerTicks=" + (p.tickCount - packetPosePlayerTick)
                    + " serverYaw=" + p.getYRot() + " serverHeadYaw=" + p.getYHeadRot());
        });
    }
    private static void stabilizeBtp() throws Exception {
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object pose = rotation.getConstructor(float.class, float.class).newInstance(bodyYaw, bodyPitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, pose);
        field(camera.getClass(), "playerRotation").set(camera, pose);
    }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void add(int delay, Action action) { steps.add(new Step(delay, action)); }
    private static void awaitClientFixture(Minecraft mc, String item, String block, String label) throws Exception {
        BlockState state = mc.level.getBlockState(TARGET);
        boolean ready = state.is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(block)))
                && mc.player.position().distanceToSqr(POSITION) < .04
                && mc.player.getMainHandItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)))
                && !mc.player.getMainHandItem().has(connectionType);
        if (!ready) {
            if (++readinessTicks > 1200) throw new IllegalStateException("Powergrid client fixture did not synchronize " + label
                    + " actualState=" + state + " position=" + mc.player.position() + " held=" + mc.player.getMainHandItem());
            if (readinessTicks == 1 || readinessTicks % 200 == 0) log.accept("POWERGRID WAIT_CLIENT " + label + " state=" + state + " position=" + mc.player.position());
            steps.addFirst(new Step(0, m -> awaitClientFixture(m, item, block, label))); return;
        }
        Class<?> electric = Class.forName("org.patryk3211.powergrid.electricity.base.IElectric");
        if (!electric.isInstance(state.getBlock())) throw new IllegalStateException("Loaded connector does not implement IElectric: " + state.getBlock().getClass());
        log.accept("POWERGRID CLIENT_READY " + label + " state=" + state + " held=" + mc.player.getMainHandItem());
    }
    private static void ordered(Minecraft mc, Consumer<ServerPlayer> action) {
        pendingTicks = 0; pending = QaPacketBarrier.send(mc, action);
    }
    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        serverCallbacks.add(completed); pendingTicks = 0; pending = completed;
        server.execute(() -> {
            Throwable failure = null;
            try { action.accept(server.getPlayerList().getPlayer(id)); }
            catch (Throwable error) { failure = error; }
            finally {
                // This is never cancelled. It records completion of the actual callback,
                // including mutations after any nested chunk-loading managedBlock.
                if (failure == null) completed.complete(null); else completed.completeExceptionally(failure);
            }
        });
    }
}
