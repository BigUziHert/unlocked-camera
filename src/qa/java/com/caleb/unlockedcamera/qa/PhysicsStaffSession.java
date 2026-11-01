package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.LevelChunk;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real staff selection and server drag targets on a reversibly controlled Sable sublevel. */
public final class PhysicsStaffSession {
    private static final ArrayDeque<Step> steps = new ArrayDeque<>();
    private static final Map<Field, Object> cameraState = new LinkedHashMap<>();
    private static final List<CompletableFuture<Void>> serverCallbacks = new ArrayList<>();
    private static boolean registered, running, restoring, motionStarted, oldLock, lockSnapshotTaken;
    private static boolean restorationRequested, restorationInFlight;
    private static String finishStatus;
    private static boolean oldToggle, oldShoulder, oldShift, useShoulder, freelookCandidates, currentCandidate, oldFreelook, oldFreelookEnabled, oldKeepDirection;
    private static KeyMapping freelookKey;
    private static double oldAmount, oldMax;
    private static int wait, checks, failures, readinessTicks, pendingTicks;
    private static Consumer<String> output;
    private static CompletableFuture<?> pending;
    private static Snapshot snapshot;
    private static CameraType oldType;
    private static Object serverShip, staff, mouse;
    private static UUID shipId;
    private static BlockPos plotTarget;
    private static Vec3 cameraOrigin, cameraDirection, initialWorldTarget, previousServerGoal, fixturePosition;
    private static float cameraYaw, cameraPitch, bodyYaw, bodyPitch;
    private static double dragDistance;
    private static Vec3 capturedSelectionTarget;
    private static int capturedSelectionTick;
    private record Snapshot(Vec3 position, float yaw, float pitch, GameType mode, boolean flying,
                            int selected, List<ItemStack> inventory) {}
    private record Step(int delay, Action action) {}
    @FunctionalInterface private interface Action { void run(Minecraft mc) throws Exception; }
    private PhysicsStaffSession() {}

    public static void run(Minecraft mc, Consumer<String> log, boolean useFreelook) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("Idle integrated-server world required");
        if (field(SableMotionProbe.class, "context").get(null) != null) throw new IllegalStateException("Another motion test is active");
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(PhysicsStaffSession::tick);
            NeoForge.EVENT_BUS.addListener(PhysicsStaffSession::frame);
            registered = true;
        }
        freelookCandidates = useFreelook; currentCandidate = false;
        output = message -> log.accept(message.replace("PHYSICS_STAFF", useFreelook ? "PHYSICS_STAFF_FREELOOK" : "PHYSICS_STAFF"));
        checks = failures = wait = readinessTicks = pendingTicks = 0; pending = null; snapshot = null; serverShip = null;
        restoring = restorationRequested = restorationInFlight = motionStarted = lockSnapshotTaken = useShoulder = false;
        shipId = null; serverCallbacks.clear();
        bodyYaw = 90; bodyPitch = 0;
        cameraOrigin = cameraDirection = previousServerGoal = null; steps.clear(); cameraState.clear();
        oldType = mc.options.getCameraType(); oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get(); oldShift = mc.options.keyShift.isDown();
        freelookKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        oldFreelook = freelookKey.isDown(); oldFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get();
        oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "shoulderSide", "freelookYaw", "freelookPitch"}) {
            Field f = field(UnlockedCameraClient.class, name); cameraState.put(f, f.get(null));
        }
        staff = Class.forName("dev.simulated_team.simulated.SimulatedClient").getField("PHYSICS_STAFF_CLIENT_HANDLER").get(null);
        mouse = Class.forName("dev.simulated_team.simulated.index.SimClickInteractions").getField("PHYSICS_STAFF_MANAGER").get(null);
        if (staff.getClass().getMethod("getDragSession").invoke(staff) != null) throw new IllegalStateException("Existing staff drag must finish first");
        running = true;
        output.accept("PHYSICS_STAFF START version=paired-scenario mode=" + (freelookCandidates ? "first_person_freelook_key_held" : "shoulder")
                + " keepDirection=" + (freelookCandidates ? false : oldKeepDirection)
                + " scope=real-selection-and-server-drag-session; positive-control=first-person-disabled; motion=bounded-kinematic; force-displacement=not-measured");
        add(45, m -> server(m, p -> {
            QaPacketBarrier.install(m.getSingleplayerServer());
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getAbilities().flying, p.getInventory().selected, inventory);
            findSeat(p.serverLevel());
            Object handler = serverHandler(p.serverLevel());
            oldLock = (boolean) handler.getClass().getMethod("isLocked", Class.forName("dev.ryanhcode.sable.sublevel.SubLevel")).invoke(handler, serverShip);
            lockSnapshotTaken = true;
            if (!sessions(handler).isEmpty()) throw new IllegalStateException("Existing server staff sessions in copied world");
            Vec3 seat = worldTarget(serverShip);
            initialWorldTarget = seat;
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities();
            p.getInventory().selected = 0;
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("simulated:creative_physics_staff"))));
            p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY); p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
            fixturePosition = seat.add(.95, .3, -2.4);
            p.teleportTo(p.serverLevel(), fixturePosition.x, fixturePosition.y, fixturePosition.z, 90, 0);
            output.accept("PHYSICS_STAFF FIXTURE ship=" + shipId + " plot=" + plotTarget + " world=" + seat + " originallyLocked=" + oldLock);
        }));
        add(0, PhysicsStaffSession::awaitClientFixture);
        add(8, m -> {
            m.setScreen(null); m.options.keyShift.setDown(false); m.player.setShiftKeyDown(false);
            m.player.getInventory().selected = 0;
            ClientConfig.ENABLE_FREELOOK.set(true); freelookKey.setDown(false);
            if (freelookCandidates) ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
            ClientConfig.TOGGLE_MOD.set(false); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field(UnlockedCameraClient.class, "active").setBoolean(null, false);
            field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4);
            field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
            field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
            field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, 0);
            field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, 0);
            m.options.setCameraType(CameraType.FIRST_PERSON);
            SableMotionProbe.start(m, output, false); motionStarted = true;
        });
        add(20, m -> server(m, p -> {
            Object context = field(SableMotionProbe.class, "context").get(null);
            if (context == null) throw new IllegalStateException("Motion preservation context failed to start");
            if (!shipId.equals(field(context.getClass(), "id").get(context))) throw new IllegalStateException("Motion preservation chose another ship");
            field(context.getClass(), "settling").setInt(context, 1000000);
            ((Vector3d) field(context.getClass(), "destination").get(context)).set((Vector3dc) field(context.getClass(), "originalPosition").get(context));
            ((Quaterniond) field(context.getClass(), "destinationOrientation").get(context)).set((Quaterniond) field(context.getClass(), "originalOrientation").get(context));
        }));
        addDragCase("first-person-control", false);
        add(0, m -> {
            if (failures != 0) throw new IllegalStateException("First-person control failed; shoulder comparison is inconclusive");
        });
        addDragCase(freelookCandidates ? "stationary-freelook" : "stationary-shoulder", true);
        add(55, m -> server(m, p -> {
            readinessTicks = 0;
            Object context = field(SableMotionProbe.class, "context").get(null);
            if (context == null) throw new IllegalStateException("Motion context missing");
            field(context.getClass(), "settling").setInt(context, 1);
        }));
        add(0, PhysicsStaffSession::awaitMovingPose);
        add(0, m -> {
            Vec3 current = worldTarget(clientShip(m));
            double displacement = current.distanceTo(initialWorldTarget);
            result("moving-network-pose", displacement > .02, "seatDisplacement=" + displacement + " current=" + current);
        });
        addDragCase(freelookCandidates ? "moving-freelook" : "moving-shoulder", true);
        add(0, m -> finish(m, "DONE"));
    }

    private static void addDragCase(String label, boolean shoulderMode) {
        add(10, m -> {
            currentCandidate = shoulderMode;
            useShoulder = shoulderMode && !freelookCandidates;
            ClientConfig.TOGGLE_MOD.set(shoulderMode);
            field(UnlockedCameraClient.class, "active").setBoolean(null, useShoulder);
            freelookKey.setDown(shoulderMode && freelookCandidates);
            m.options.setCameraType(useShoulder ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
            m.player.getInventory().selected = 0;
            Object ship = clientShip(m);
            Vec3 target = worldTarget(ship);
            capturedSelectionTarget = target;
            capturedSelectionTick = m.player.tickCount;
            cameraOrigin = useShoulder ? m.player.getEyePosition().add(.95, 0, -4) : m.player.getEyePosition();
            setAim(target.subtract(cameraOrigin));
            bodyYaw = shoulderMode ? (freelookCandidates ? 0 : 90) : cameraYaw; bodyPitch = shoulderMode ? 0 : cameraPitch;
            applyBody(m); applyCamera(m);
            previousServerGoal = null;
        });
        add(0, m -> {
            // The motion controller advances on server ticks while the preceding
            // fixture delay counts client ticks. Re-aim at selection time, rather
            // than asking a cached ten-tick-old ray to hit the same moving seat.
            Object selectionShip = clientShip(m);
            Vec3 currentTarget = worldTarget(selectionShip);
            Object logicalPose = selectionShip.getClass().getMethod("logicalPose").invoke(selectionShip);
            Object renderPose = selectionShip.getClass().getMethod("renderPose", float.class).invoke(selectionShip, 1f);
            Vec3 localPoint = new Vec3(plotTarget.getX() + .5, plotTarget.getY() + .25, plotTarget.getZ() + .5);
            Vec3 renderTarget = (Vec3) renderPose.getClass().getMethod("transformPosition", Vec3.class).invoke(renderPose, localPoint);
            setAim(currentTarget.subtract(cameraOrigin));
            if (!shoulderMode) { bodyYaw = cameraYaw; bodyPitch = cameraPitch; }
            applyBody(m); applyCamera(m);
            m.gameRenderer.pick(1);
            double range = Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem").getField("RANGE").getFloat(null);
            Vec3 rawOrigin = m.gameRenderer.getMainCamera().getPosition();
            var look = m.gameRenderer.getMainCamera().getLookVector();
            Vec3 rawDirection = new Vec3(look.x(), look.y(), look.z());
            double depth = Math.max(0, m.player.getEyePosition().subtract(rawOrigin).dot(rawDirection));
            Vec3 gapOrigin = rawOrigin.add(rawDirection.scale(depth));
            Vec3 end = rawOrigin.add(rawDirection.scale(range + depth));
            HitResult rawReference = m.level.clip(new ClipContext(rawOrigin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, m.player));
            HitResult gapReference = m.level.clip(new ClipContext(gapOrigin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, m.player));
            HitResult hit = shoulderMode ? UnlockedCameraClient.crosshairPick(m.player, range, 1, false) : m.player.pick(range, 1, false);
            output.accept("PHYSICS_STAFF SELECTION_GEOMETRY " + label + " capturedPlayerTick=" + capturedSelectionTick
                    + " currentPlayerTick=" + m.player.tickCount + " targetMoved=" + currentTarget.distanceTo(capturedSelectionTarget)
                    + " localTarget=" + localPoint + " capturedWorldTarget=" + capturedSelectionTarget
                    + " currentLogicalTarget=" + currentTarget + " currentRenderTarget=" + renderTarget
                    + " logicalPose=" + logicalPose + " renderPose=" + renderPose
                    + " cameraOrigin=" + rawOrigin + " gapOrigin=" + gapOrigin + " direction=" + rawDirection
                    + " independentClip=OUTLINE/NONE rawReference=" + describe(rawReference)
                    + " gapReference=" + describe(gapReference) + " actual=" + describe(hit)
                    + " retargetedImmediatelyBeforePick=true;no_server_pose_or_staff_oracle_change=true");
            boolean correct = hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(plotTarget);
            result(label + " plot-target", correct, "hit=" + describe(hit));
            if (!correct) throw new IllegalStateException("Staff fixture target not picked");
            HitResult body = m.player.pick(range, 1, false);
            if (shoulderMode) result(label + " distinct-from-body", !(body instanceof BlockHitResult block) || !block.getBlockPos().equals(plotTarget), "bodyHit=" + describe(body));
            staff.getClass().getMethod("tick").invoke(staff);
            Object helper = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
            Object owner = helper.getClass().getMethod("getContainingClient", net.minecraft.core.Position.class).invoke(helper, hit.getLocation());
            boolean holding = field(staff.getClass(), "holdingStaff").getBoolean(staff);
            output.accept("PHYSICS_STAFF INPUT " + label + " selected=" + m.player.getInventory().selected
                    + " item=" + BuiltInRegistries.ITEM.getKey(m.player.getMainHandItem().getItem())
                    + " holdingStaff=" + holding + " state=" + field(staff.getClass(), "state").get(staff)
                    + " freelookKey=" + freelookKey.isDown() + " cameraType=" + m.options.getCameraType()
                    + " range=" + range + " hitLocation=" + hit.getLocation() + " hitOwner="
                    + (owner == null ? "null" : owner.getClass().getMethod("getUniqueId").invoke(owner)));
            if (!holding) throw new IllegalStateException("Fixture inventory not recognized as staff");
            if (owner == null) throw new IllegalStateException("Staff hit location has no client sublevel owner");
            Object inputResult = mouse.getClass().getMethod("onUse", int.class, int.class, KeyMapping.class).invoke(mouse, 1, 1, m.options.keyUse);
            Object session = staff.getClass().getMethod("getDragSession").invoke(staff);
            output.accept("PHYSICS_STAFF INPUT_RESULT " + label + " result=" + inputResult + " state=" + field(staff.getClass(), "state").get(staff) + " hasSession=" + (session != null));
            if (session == null) throw new IllegalStateException("Staff did not start client drag");
            Object selectedShip = session.getClass().getMethod("dragSubLevel").invoke(session);
            UUID selected = (UUID) selectedShip.getClass().getMethod("getUniqueId").invoke(selectedShip);
            Vector3dc anchor = (Vector3dc) session.getClass().getMethod("dragLocalAnchor").invoke(session);
            dragDistance = ((Number) session.getClass().getMethod("distance").invoke(session)).doubleValue();
            boolean correctSession = selected.equals(shipId) && vector(anchor).distanceTo(plotTarget.getCenter()) < 1e-6;
            result(label + " client-drag", correctSession, "ship=" + selected + " anchor=" + anchor + " distance=" + dragDistance);
            staff.getClass().getMethod("tick").invoke(staff);
        });
        add(8, m -> {});
        add(2, m -> observeServer(m, label + " initial", false));
        add(8, m -> {
            cameraYaw += 15f; cameraDirection = Vec3.directionFromRotation(cameraPitch, cameraYaw);
            if (!shoulderMode) bodyYaw = cameraYaw;
            applyBody(m); applyCamera(m); staff.getClass().getMethod("tick").invoke(staff);
        });
        add(2, m -> observeServer(m, label + " changed-aim", true));
        add(10, m -> stopStaff(m));
        add(0, m -> ordered(m, p -> result(label + " stop-server-session", !sessions(serverHandler(p.serverLevel())).containsKey(p.getUUID()), "player=" + p.getUUID())));
    }

    private static void observeServer(Minecraft mc, String label, boolean requireChange) {
        Vec3 origin = cameraOrigin, direction = cameraDirection, eye = mc.player.getEyePosition();
        double distance = dragDistance;
        ordered(mc, p -> {
            Object session = sessions(serverHandler(p.serverLevel())).get(p.getUUID());
            if (session == null) { result(label + " server-drag", false, "no session"); return; }
            Object sub = field(session.getClass(), "subLevel").get(session);
            UUID id = (UUID) sub.getClass().getMethod("getUniqueId").invoke(sub);
            Vec3 anchor = vector((Vector3dc) field(session.getClass(), "plotAnchor").get(session));
            Vec3 relative = vector((Vector3dc) field(session.getClass(), "playerRelativeGoal").get(session));
            Vec3 goal = eye.add(relative);
            double rayError = goal.subtract(origin).cross(direction).length();
            double distanceError = Math.abs(relative.length() - distance);
            boolean forward = goal.subtract(origin).dot(direction) > 0;
            double changed = previousServerGoal == null ? 0 : relative.distanceTo(previousServerGoal);
            boolean pass = id.equals(shipId) && anchor.distanceTo(plotTarget.getCenter()) < 1e-6
                    && rayError < .003 && distanceError < .003 && forward && (!requireChange || changed > .1);
            result(label + " server-drag", pass, "ship=" + id + " anchor=" + anchor + " relativeGoal=" + relative
                    + " rayError=" + rayError + " distanceError=" + distanceError + " goalChange=" + changed);
            previousServerGoal = relative;
        });
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (restorationRequested) {
                if (serverCallbacks.stream().anyMatch(callback -> !callback.isDone())) {
                    if (++pendingTicks % 200 == 0) output.accept("PHYSICS_STAFF DRAIN_PENDING awaitingActualServerCallbackFinally=true");
                    return;
                }
                restorationRequested = false; finish(mc, finishStatus); return;
            }
            if (!restoring && mc.player != null) {
                applyBody(mc);
                if (cameraOrigin != null) applyCamera(mc);
            }
            if (pending != null) {
                if (!pending.isDone()) {
                    if (++pendingTicks > 1200 && !restoring && !restorationInFlight) throw new IllegalStateException("Staff server/packet barrier timed out");
                    if ((restoring || restorationInFlight) && pendingTicks % 200 == 0) output.accept("PHYSICS_STAFF RESTORE_PENDING orderedServerCallback=true");
                    return;
                }
                pending.join(); pending = null; pendingTicks = 0;
            }
            if (restoring) {
                if (field(SableMotionProbe.class, "context").get(null) != null) return;
                restoring = false;
                restorationInFlight = true;
                if (snapshot != null) {
                    bodyYaw = snapshot.yaw; bodyPitch = snapshot.pitch;
                    applyBody(mc);
                }
                ordered(mc, p -> {
                    if (serverShip != null && lockSnapshotTaken) {
                        Object handler = serverHandler(p.serverLevel());
                        handler.getClass().getMethod("stopDragging", UUID.class).invoke(handler, p.getUUID());
                        boolean locked = (boolean) handler.getClass().getMethod("isLocked", Class.forName("dev.ryanhcode.sable.sublevel.SubLevel")).invoke(handler, serverShip);
                        if (locked != oldLock) handler.getClass().getMethod("toggleLock", UUID.class).invoke(handler, shipId);
                    }
                    restorePlayer(p);
                });
                steps.add(new Step(0, m -> {
                    running = false; restorationInFlight = false;
                    if (snapshot != null) {
                        m.player.getInventory().selected = snapshot.selected;
                        bodyYaw = snapshot.yaw; bodyPitch = snapshot.pitch; applyBody(m);
                    }
                    output.accept("PHYSICS_STAFF RESTORED checks=" + checks + " failures=" + failures);
                }));
                return;
            }
            applyBody(mc);
            if (cameraOrigin != null) applyCamera(mc);
            if (wait-- > 0) return;
            Step step = steps.poll(); if (step == null) return;
            step.action.run(mc); wait = step.delay;
        } catch (Throwable e) {
            failures++;
            output.accept("PHYSICS_STAFF ERROR " + e); e.printStackTrace();
            try { finish(mc, "ABORT"); }
            catch (Exception restore) { running = false; output.accept("PHYSICS_STAFF RESTORE_ERROR " + restore); }
        }
    }

    private static void frame(RenderFrameEvent.Post event) {
        if (!running || restoring || restorationRequested || cameraOrigin == null) return;
        try { applyCamera(Minecraft.getInstance()); } catch (Exception e) { output.accept("PHYSICS_STAFF CAMERA_ERROR " + e); }
    }

    private static void finish(Minecraft mc, String status) throws Exception {
        steps.clear();
        if (serverCallbacks.stream().anyMatch(callback -> !callback.isDone())) {
            restorationRequested = true; finishStatus = status; pending = null; pendingTicks = 0;
            output.accept("PHYSICS_STAFF DRAIN_PENDING status=" + status + " awaitingActualServerCallbackFinally=true");
            return;
        }
        if (pending != null && !pending.isDone()) pending.cancel(false);
        steps.clear(); cameraOrigin = null; stopStaff(mc);
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        for (var entry : cameraState.entrySet()) entry.getKey().set(null, entry.getValue());
        mc.options.setCameraType(oldType); mc.options.keyShift.setDown(oldShift); mc.player.setShiftKeyDown(oldShift);
        freelookKey.setDown(oldFreelook); ClientConfig.ENABLE_FREELOOK.set(oldFreelookEnabled);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        if (motionStarted) SableMotionProbe.stop(mc);
        restoring = true; pending = null;
        output.accept("PHYSICS_STAFF " + status + " checks=" + checks + " failures=" + failures + " restoration=pending");
    }

    private static void restorePlayer(ServerPlayer p) {
        if (snapshot == null) return;
        p.setGameMode(snapshot.mode); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
        for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
        p.getInventory().selected = snapshot.selected;
        p.teleportTo(p.serverLevel(), snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
        p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
    }

    private static void stopStaff(Minecraft mc) throws Exception {
        if (staff.getClass().getMethod("getDragSession").invoke(staff) != null) {
            mouse.getClass().getMethod("onUse", int.class, int.class, KeyMapping.class).invoke(mouse, 1, 1, mc.options.keyUse);
        }
    }

    private static Object serverHandler(ServerLevel level) throws Exception {
        return Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler").getMethod("get", ServerLevel.class).invoke(null, level);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ?> sessions(Object handler) throws Exception { return (Map<UUID, ?>) field(handler.getClass(), "draggingSessions").get(handler); }

    private static Object clientShip(Minecraft mc) throws Exception {
        Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        Object current = container.getMethod("getContainer", Level.class).invoke(null, mc.level);
        Object ship = container.getMethod("getSubLevel", UUID.class).invoke(current, shipId);
        if (ship == null) throw new IllegalStateException("Client not tracking test ship");
        return ship;
    }

    private static void findSeat(ServerLevel level) throws Exception {
        Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        Object current = container.getMethod("getContainer", Level.class).invoke(null, level);
        List<?> ships = (List<?>) container.getMethod("getAllSubLevels").invoke(current);
        for (Object ship : ships) {
            Object plot = ship.getClass().getMethod("getPlot").invoke(ship);
            Collection<?> chunks = (Collection<?>) plot.getClass().getMethod("getLoadedChunks").invoke(plot);
            for (Object holder : chunks) {
                LevelChunk chunk = (LevelChunk) holder.getClass().getMethod("getChunk").invoke(holder);
                for (int si = 0; si < chunk.getSections().length; si++) {
                    var section = chunk.getSection(si); if (section.hasOnlyAir()) continue;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        String id = BuiltInRegistries.BLOCK.getKey(section.getBlockState(x,y,z).getBlock()).toString();
                        if (!id.startsWith("create:") || !id.endsWith("_seat")) continue;
                        plotTarget = new BlockPos(chunk.getPos().getMinBlockX()+x, level.getSectionYFromSectionIndex(si)*16+y, chunk.getPos().getMinBlockZ()+z);
                        serverShip = ship; shipId = (UUID) ship.getClass().getMethod("getUniqueId").invoke(ship); return;
                    }
                }
            }
        }
        throw new IllegalStateException("No existing loaded sublevel seat");
    }

    private static Vec3 worldTarget(Object ship) throws Exception {
        Object pose = ship.getClass().getMethod("logicalPose").invoke(ship);
        return (Vec3) pose.getClass().getMethod("transformPosition", Vec3.class).invoke(pose,
                new Vec3(plotTarget.getX()+.5, plotTarget.getY()+.25, plotTarget.getZ()+.5));
    }

    private static void setAim(Vec3 direction) {
        cameraDirection = direction.normalize();
        cameraYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        cameraPitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.x*direction.x+direction.z*direction.z)));
    }
    private static void applyCamera(Minecraft mc) throws Exception {
        boolean free = currentCandidate && freelookCandidates;
        freelookKey.setDown(free);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, free ? cameraYaw - bodyYaw : 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, free ? cameraPitch - bodyPitch : 0);
        Camera camera = mc.gameRenderer.getMainCamera();
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true); position.invoke(camera, cameraOrigin);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        rotation.setAccessible(true); rotation.invoke(camera, cameraYaw, cameraPitch, 0f);
    }
    private static void applyBody(Minecraft mc) throws Exception {
        mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw; mc.player.setXRot(bodyPitch); mc.player.xRotO = bodyPitch;
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object customCamera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (customCamera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object pose = rotation.getConstructor(float.class, float.class).newInstance(bodyYaw, bodyPitch);
        customCamera.getClass().getMethod("setCameraRotation", rotation).invoke(customCamera, pose);
        field(customCamera.getClass(), "playerRotation").set(customCamera, pose);
    }
    private static Vec3 vector(Vector3dc v) { return new Vec3(v.x(), v.y(), v.z()); }
    private static String describe(HitResult h) { return h == null ? "null" : h.getType()+"@"+(h instanceof BlockHitResult b ? b.getBlockPos() : h.getLocation()); }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void add(int delay, Action action) { steps.add(new Step(delay, action)); }
    private static void awaitClientFixture(Minecraft mc) throws Exception {
        mc.player.getInventory().selected = 0;
        boolean tracked;
        try { clientShip(mc); tracked = true; } catch (IllegalStateException missing) { tracked = false; }
        String block = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(plotTarget).getBlock()).toString();
        boolean ready = tracked && block.startsWith("create:") && block.endsWith("_seat")
                && mc.player.position().distanceToSqr(fixturePosition) < .04
                && mc.player.getMainHandItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.parse("simulated:creative_physics_staff")));
        if (!ready) {
            if (++readinessTicks > 1200) throw new IllegalStateException("Staff fixture did not synchronize tracked=" + tracked + " block=" + block
                    + " position=" + mc.player.position() + " expected=" + fixturePosition + " held=" + mc.player.getMainHandItem());
            if (readinessTicks == 1 || readinessTicks % 200 == 0) output.accept("PHYSICS_STAFF WAIT_CLIENT tracked=" + tracked + " block=" + block + " position=" + mc.player.position());
            steps.addFirst(new Step(0, PhysicsStaffSession::awaitClientFixture)); return;
        }
        output.accept("PHYSICS_STAFF CLIENT_READY ship=" + shipId + " plot=" + plotTarget + " position=" + mc.player.position());
        ordered(mc, p -> {
            if (field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(p.connection) != null)
                throw new IllegalStateException("Staff teleport has not been acknowledged");
        });
    }
    private static void awaitMovingPose(Minecraft mc) throws Exception {
        Vec3 current = worldTarget(clientShip(mc));
        if (current.distanceTo(initialWorldTarget) > .02) return;
        if (++readinessTicks > 1200) throw new IllegalStateException("Staff moving network pose did not arrive");
        if (readinessTicks == 1 || readinessTicks % 200 == 0) output.accept("PHYSICS_STAFF WAIT_MOVING_POSE world=" + current);
        steps.addFirst(new Step(0, PhysicsStaffSession::awaitMovingPose));
    }
    @FunctionalInterface private interface ServerAction { void run(ServerPlayer player) throws Exception; }
    private static void ordered(Minecraft mc, ServerAction action) {
        pendingTicks = 0;
        pending = QaPacketBarrier.send(mc, p -> { try { action.run(p); } catch (Exception e) { throw new IllegalStateException(e); } });
    }
    private static void server(Minecraft mc, ServerAction action) {
        var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        serverCallbacks.add(completed); pendingTicks = 0; pending = completed;
        server.execute(() -> {
            Throwable failure = null;
            try { action.run(server.getPlayerList().getPlayer(id)); }
            catch (Throwable error) { failure = error; }
            finally {
                if (failure == null) completed.complete(null); else completed.completeExceptionally(failure);
            }
        });
    }
    private static void result(String label, boolean pass, String detail) { checks++; if (!pass) failures++; output.accept("PHYSICS_STAFF " + (pass?"PASS ":"FAIL ")+label+" "+detail); }
}
