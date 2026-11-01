package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
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
import net.neoforged.bus.api.EventPriority;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Actual renderer picks and client/server module attach/wrench requests on stationary consoles. */
public final class AeroworksModuleProbe {
    private static final Vec3 PLAYER = new Vec3(1710.5, 210, 190.5);
    private static final BlockPos TARGET = new BlockPos(1710, 210, 193);
    private static final String ACK = "unlockedcamera_qa_aeroworks_ack";
    private static final String[] CONSOLES = {"aeroworks:control_desk", "aeroworks:copycat_control_desk", "aeroworks:control_stand", "aeroworks:copycat_control_stand"};
    private static final String[] MODES = {"first_person", "offset", "freelook"};
    private static final AABB AREA = new AABB(1707, 209, 187, 1715, 215, 198);
    private static final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    private static final Map<Field, Object> fields = new LinkedHashMap<>();
    private static final Map<String, Boolean> baselines = new LinkedHashMap<>();
    private static final Map<String, Vec3> fixtureAimPoints = new LinkedHashMap<>();
    private static final List<Item> items = new ArrayList<>();
    private static final List<Integer> reviewCases = new ArrayList<>();
    private static boolean review;
    private static boolean regression;
    private static int poseReadyPlayerTick;
    private static int preSamples;
    private static boolean handControlsDone;
    private static Class<?> consoleClass, moduleItemClass, moduleTypeClass;
    private static Method cameraPosition, cameraRotation;
    private static Consumer<String> log;
    private static CompletableFuture<?> task;
    private static Snapshot snapshot;
    private static Set<UUID> originals;
    private static boolean registered, running, restoring, fixtureReady, advancing;
    private static boolean oldToggle, oldShoulder, oldFreelookEnabled, oldKeepDirection, oldFreelook, oldShift, oldPause;
    private static double oldAmount, oldMax;
    private static CameraType oldType;
    private static KeyMapping freelookKey;
    private static int index, phase, age, caseAge, token, checks, failures, limited, restorePhase;
    private static volatile int socket;
    private static volatile Vec3 point;
    private static volatile String setupIssue;
    private static volatile Observation ack;
    private static volatile RestoreObservation restoreObservation;
    private static Object moduleType;
    private static String clientResult, cameraMount, bodyMount, hitDescription;
    private static boolean cameraValid;
    private static float bodyYaw, bodyPitch;
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode, boolean flying, int slot, List<ItemStack> inventory) {}
    private record Observation(int token, boolean teleportReady, boolean inReach, String modules, String expectedItem,
                               int moduleCount, int returnedCount, String bodyMount, float yaw, float pitch, String blockState,
                               boolean fixtureStateValid, boolean heldValid, float headYaw, int playerTick, Vec3 eye, Vec3 view) {}
    private record ModuleState(String modules, String expectedItem, int count) {}
    private record RestoreObservation(boolean state, boolean pose, boolean teleportReady, int slots, int blocks,
                                      int entities, Vec3 position, float yaw, float pitch) {}
    @FunctionalInterface private interface ServerAction { void run(ServerPlayer player) throws Exception; }
    private AeroworksModuleProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        runInternal(mc, output, false, false);
    }

    /** Paired repetitions of only original freelook failures with successful original controls. */
    public static void runFreelookReview(Minecraft mc, Consumer<String> output) throws Exception {
        runInternal(mc, output, true, false);
    }

    public static void runRegression(Minecraft mc, Consumer<String> output) throws Exception {
        runInternal(mc, output, false, true);
    }

    private static void runInternal(Minecraft mc, Consumer<String> output, boolean reviewing, boolean verifyFix) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isPassenger())
            throw new IllegalStateException("Idle integrated-server player required");
        var gamePath = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!gamePath.endsWith(java.nio.file.Path.of("build", "modpack-test", "game"))
                && !gamePath.endsWith(java.nio.file.Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Only disposable QA copy may run this fixture");
        consoleClass = Class.forName("com.mred231.aeroworks.content.controls.console.ConsoleBlockEntity");
        moduleItemClass = Class.forName("com.mred231.aeroworks.content.controls.module.ModuleItem");
        moduleTypeClass = Class.forName("com.mred231.aeroworks.content.controls.module.ModuleType");
        items.clear(); for (Item item : BuiltInRegistries.ITEM) if (moduleItemClass.isInstance(item)) items.add(item);
        items.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
        if (items.isEmpty()) throw new IllegalStateException("No loaded AeroWorks modules");
        review = reviewing; regression = verifyFix; reviewCases.clear();
        if (review) selectReviewCases(gamePath.resolve("qa/results.log"));
        log = regression ? line -> output.accept(line.replaceFirst("^AEROWORKS_MODULE", "AEROWORKS_MODULE_REGRESSION"))
                : review ? line -> output.accept(line.replaceFirst("^AEROWORKS_MODULE", "AEROWORKS_MODULE_FREELOOK_RECHECK")) : output;
        if (review && reviewCases.isEmpty()) {
            log.accept("AEROWORKS_MODULE SKIP no-original-freelook-failures-with-positive-controls");
            log.accept("AEROWORKS_MODULE RESTORED checks=0 failures=0 limited=0 reason=no-eligible-cases stateUntouched=true");
            return;
        }
        index = phase = age = caseAge = checks = failures = limited = 0; handControlsDone = false;
        restoring = fixtureReady = advancing = false; restorePhase = 0; task = null; restoreObservation = null;
        snapshot = null; blocks.clear(); fields.clear(); baselines.clear(); fixtureAimPoints.clear();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "freelookYaw", "freelookPitch", "shoulderSide"}) {
            Field f = field(UnlockedCameraClient.class, name); fields.put(f, f.get(null));
        }
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get(); oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        freelookKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        oldFreelook = freelookKey.isDown(); oldShift = mc.options.keyShift.isDown(); oldType = mc.options.getCameraType(); oldPause = mc.options.pauseOnLostFocus;
        cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        cameraPosition.setAccessible(true); cameraRotation.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(AeroworksModuleProbe::tick);
            NeoForge.EVENT_BUS.addListener(AeroworksModuleProbe::frame);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, AeroworksModuleProbe::preHigh);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, AeroworksModuleProbe::preLow);
            registered = true;
        }
        running = true; mc.setScreen(null); mc.options.pauseOnLostFocus = false;
        log.accept("AEROWORKS_MODULE START modules=" + items.size() + " consoles=" + CONSOLES.length + " cases=" + total()
                + " modes=" + (review ? "first_person,real_first_person_freelook repeats=3 preUsePlayerTicks=3" : "first_person,offset,real_first_person_freelook")
                + " keepDirection=false scope=actual-mainhand-attach-and-wrench-remove;stationary-world;default-module-components");
        task = server(mc, p -> {
            QaPacketBarrier.install(mc.getSingleplayerServer());
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(), p.getAbilities().flying, p.getInventory().selected, inventory);
            originals = new HashSet<>(); for (Entity entity : snapshot.level.getAllEntities()) if (entity != null) originals.add(entity.getUUID());
            if (!snapshot.level.getEntities(p, AREA).isEmpty()) throw new IllegalStateException("Existing fixture entities");
            for (BlockPos pos : BlockPos.betweenClosed(1707, 209, 187, 1714, 214, 197)) {
                if (snapshot.level.getBlockEntity(pos) != null) throw new IllegalStateException("Existing fixture block entity " + pos);
                blocks.put(pos.immutable(), snapshot.level.getBlockState(pos));
            }
            fixtureReady = true;
            for (BlockPos pos : blocks.keySet()) snapshot.level.setBlock(pos, pos.getY() == 209 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = false; p.onUpdateAbilities();
            p.teleportTo(snapshot.level, PLAYER.x, PLAYER.y, PLAYER.z, 0, 0);
            mc.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(ACK)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer sender = context.getSource().getPlayerOrException();
                        if (running && !restoring && sender.getUUID().equals(p.getUUID())) {
                            try { ack = observe(sender, IntegerArgumentType.getInteger(context, "token")); }
                            catch (Exception error) { throw new IllegalStateException(error); }
                        }
                        return 1;
                    })));
        });
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            age++; caseAge++;
            if (task != null) { if (!task.isDone()) return; task.join(); task = null; }
            if (restoring) {
                verifyRestoration(mc); return;
            }
            if (advancing) {
                advancing = false;
                if (++index == total()) { restore(mc, "DONE"); return; }
                if (index % 24 == 0) log.accept("AEROWORKS_MODULE PROGRESS " + index + "/" + total() + " failures=" + failures + " limited=" + limited);
                prepare(mc); return;
            }
            if (caseAge > 1200) { inconclusive(mc, "timeout-phase=" + phase); return; }
            mc.player.getInventory().selected = 0; mc.options.keyShift.setDown(false); mc.player.setShiftKeyDown(false); mc.player.input.shiftKeyDown = false;
            if (phase == 0) { prepare(mc); return; }
            if (phase == 1) {
                if (!setupIssue.isEmpty()) { inconclusive(mc, setupIssue); return; }
                BlockState expectedState = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(consoleId())).defaultBlockState();
                if (age < 12 || !mc.level.getBlockState(TARGET).equals(expectedState)
                        || !consoleClass.isInstance(mc.level.getBlockEntity(TARGET)) || mc.player.position().distanceToSqr(PLAYER) > .04) return;
                Item expectedHeld = removal() ? item("create:wrench") : items.get(itemIndex());
                if (!ItemStack.matches(mc.player.getMainHandItem(), new ItemStack(expectedHeld, 1)) || !mc.player.getOffhandItem().isEmpty()) return;
                Object console = mc.level.getBlockEntity(TARGET);
                ModuleState mounted = moduleState(console);
                if (removal() ? mounted.count != 1 || !id().equals(mounted.expectedItem) : mounted.count != 0) return;
                if (mode() == 0 && !chooseVisibleFixtureAim(mc, console)) {
                    inconclusive(mc, "no-visible-first-person-ray-to-compatible-root-socket=" + socket); return;
                }
                if (mode() != 0 && fixtureAimPoints.containsKey(caseKey())) point = fixtureAimPoints.get(caseKey());
                pose(mc); phase = 2; age = 0; return;
            }
            if (phase == 2) {
                pose(mc); if (age < 8) return;
                // This is the normal outgoing rotation path. Real freelook may rewrite it through its continuous aim hold.
                mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(bodyYaw, bodyPitch, mc.player.onGround()));
                requestAck(mc); phase = 3; age = 0; return;
            }
            if (phase == 3) {
                pose(mc); if (ack == null || ack.token != token) return;
                if (!ack.teleportReady) { requestAck(mc); return; }
                if (!ack.inReach || !ack.fixtureStateValid || !ack.heldValid
                        || (removal() ? ack.moduleCount != 1 || !id().equals(ack.expectedItem) : ack.moduleCount != 0)) {
                    inconclusive(mc, "invalid-server-precondition=" + ack); return;
                }
                if (review || regression) {
                    Camera camera = mc.gameRenderer.getMainCamera();
                    float[] held = regression ? UnlockedCameraClient.continuousAimAngles() : null;
                    float wantedYaw = regression ? (held == null ? bodyYaw : held[0]) : camera.getYRot();
                    float wantedPitch = regression ? (held == null ? bodyPitch : held[1]) : camera.getXRot();
                    boolean bodyReady = Math.abs(Mth.wrapDegrees(ack.yaw - wantedYaw)) < .05
                            && Math.abs(ack.pitch - wantedPitch) < .05;
                    if (!bodyReady) { poseReadyPlayerTick = -1; requestAck(mc); return; }
                    if (poseReadyPlayerTick < 0) {
                        poseReadyPlayerTick = ack.playerTick;
                        log.accept("AEROWORKS_MODULE POSE_READY " + label() + " playerTick=" + ack.playerTick
                                + " serverBodyYaw=" + ack.yaw + " serverHeadYaw=" + ack.headYaw + " serverMount=" + ack.bodyMount);
                    }
                    // A movement-packet acknowledgement does not itself run Player.serverAiStep.
                    // Wait for ordinary player ticks; never write the candidate server head rotation.
                    if (ack.playerTick < poseReadyPlayerTick + 3) { requestAck(mc); return; }
                }
                if (regression && mode() == 1 && !removal() && !handControlsDone) verifyHandPriority(mc);
                log.accept("AEROWORKS_MODULE BEFORE " + label() + " expectedSocket=" + socket + " seededForRemoval=" + removal()
                        + " serverBodyMount=" + ack.bodyMount + " serverYawPitch=" + ack.yaw + "," + ack.pitch
                        + " serverHeadYaw=" + ack.headYaw + " playerTick=" + ack.playerTick
                        + " settledPlayerTicks=" + (review || regression ? ack.playerTick - poseReadyPlayerTick : -1)
                        + " serverEye=" + ack.eye + " serverView=" + ack.view + " state=" + ack.blockState
                        + (regression ? " directInteractionAim=" + Arrays.toString(com.caleb.unlockedcamera.client.AddonInteractionAim.angles(mc))
                                + " cachedAim=" + Arrays.toString(UnlockedCameraClient.continuousAimAngles()) : ""));
                Object console = mc.level.getBlockEntity(TARGET);
                Camera camera = mc.gameRenderer.getMainCamera(); var look = camera.getLookVector();
                Vec3 direction = new Vec3(look.x(), look.y(), look.z()), origin = camera.getPosition();
                Object byCamera = nearest(console, origin, origin.add(direction.scale(mc.player.blockInteractionRange() + origin.distanceTo(mc.player.getEyePosition()))));
                Vec3 eye = mc.player.getEyePosition(); Object byBody = nearest(console, eye, eye.add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange())));
                cameraMount = targetText(byCamera); bodyMount = targetText(byBody);
                // Include the installed AeroWorks and Create TAIL pick mixins, not only the camera helper.
                mc.gameRenderer.pick(1); HitResult hit = mc.hitResult;
                hitDescription = hit == null ? "null" : hit.getType() + "@" + (hit instanceof BlockHitResult b ? b.getBlockPos() : hit.getLocation());
                cameraValid = expectedRoot(byCamera);
                if (!cameraValid) { inconclusive(mc, "reference-camera-ray-did-not-select-expected-socket cameraMount=" + cameraMount); return; }
                if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK || !TARGET.equals(blockHit.getBlockPos())) {
                    clientResult = "NOT_SENT";
                    if (mode() == 0) inconclusive(mc, "first-person-fixture-not-targeted cameraTarget=" + hitDescription);
                    else finishCase(mc, false, "cameraTarget=" + hitDescription + " requestSent=false");
                    return;
                }
                clientResult = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHit).toString();
                requestAck(mc); phase = 4; age = 0; return;
            }
            if (phase == 4) {
                if (ack == null || ack.token != token) return;
                boolean pass = removal() ? ack.moduleCount == 0 && ack.returnedCount == 1 : id().equals(ack.expectedItem) && ack.moduleCount == 1;
                finishCase(mc, pass, "serverModules=" + ack.modules + " returnedCount=" + ack.returnedCount
                        + " serverBodyYaw=" + ack.yaw + " serverHeadYaw=" + ack.headYaw + " playerTick=" + ack.playerTick + " state=" + ack.blockState);
            }
        } catch (Throwable error) {
            log.accept("AEROWORKS_MODULE ERROR " + labelSafe() + " " + error);
            if (restoring) { running = false; log.accept("AEROWORKS_MODULE NOT_RESTORED error=" + error); return; }
            try { restore(mc, "ABORT"); } catch (Throwable fatal) { running = false; log.accept("AEROWORKS_MODULE RESTORE_ERROR " + fatal); }
        }
    }

    private static void prepare(Minecraft mc) throws Exception {
        setupIssue = ""; ack = null; socket = -1; point = null; cameraValid = false; poseReadyPlayerTick = -1; preSamples = 0;
        cameraMount = bodyMount = "none"; clientResult = "NOT_SENT"; hitDescription = "none";
        freelookKey.setDown(false); mc.setScreen(null);
        field(UnlockedCameraClient.class, "active").setBoolean(null, false);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, 0); field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, 0);
        Method drop = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); drop.setAccessible(true); drop.invoke(null);
        moduleType = moduleItemClass.getMethod("getType").invoke(items.get(itemIndex()));
        task = server(mc, p -> {
            cleanupEntities();
            snapshot.level.setBlock(TARGET, Blocks.AIR.defaultBlockState(), 3);
            snapshot.level.setBlock(TARGET, BuiltInRegistries.BLOCK.get(ResourceLocation.parse(consoleId())).defaultBlockState(), 3);
            Object console = snapshot.level.getBlockEntity(TARGET);
            if (!consoleClass.isInstance(console)) { setupIssue = "no-console-block-entity"; return; }
            List<?> free = (List<?>) consoleClass.getMethod("freeMounts", moduleTypeClass).invoke(console, moduleType);
            for (Object spot : free) {
                Object target = spot.getClass().getMethod("target").invoke(spot);
                if (target.getClass().getMethod("subPath").invoke(target) != null) continue;
                socket = targetSocket(target);
                point = ((Vec3) spot.getClass().getMethod("center").invoke(spot)).add(Vec3.atLowerCornerOf(TARGET)); break;
            }
            if (socket < 0) { setupIssue = "no-compatible-root-socket"; return; }
            if (removal() && !(boolean) consoleClass.getMethod("mount", int.class, ItemStack.class).invoke(console, socket, new ItemStack(items.get(itemIndex())))) {
                setupIssue = "cannot-seed-independent-removal-fixture"; return;
            }
            p.getInventory().clearContent(); p.getInventory().selected = 0;
            p.getInventory().setItem(0, new ItemStack(removal() ? item("create:wrench") : items.get(itemIndex()), 1));
            p.setShiftKeyDown(false); p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
        });
        phase = 1; age = caseAge = 0;
    }

    private static void frame(RenderFrameEvent.Post ignored) {
        if (!running || restoring || !regression || point == null || phase < 2 || phase > 3) return;
        try { pose(Minecraft.getInstance()); }
        catch (Exception error) { log.accept("AEROWORKS_MODULE CAMERA_ERROR " + error); }
    }

    private static void preHigh(ClientTickEvent.Pre ignored) { samplePre("HIGHEST"); }
    private static void preLow(ClientTickEvent.Pre ignored) { if (samplePre("LOWEST")) preSamples++; }
    private static boolean samplePre(String priority) {
        if (!running || restoring || !regression || phase != 3 || preSamples >= 3) return false;
        Minecraft mc = Minecraft.getInstance(); Camera camera = mc.gameRenderer.getMainCamera();
        log.accept("AEROWORKS_MODULE PRE_SAMPLE " + label() + " priority=" + priority
                + " direct=" + Arrays.toString(com.caleb.unlockedcamera.client.AddonInteractionAim.angles(mc))
                + " cached=" + Arrays.toString(UnlockedCameraClient.continuousAimAngles())
                + " camera=" + camera.getYRot() + "," + camera.getXRot() + " origin=" + camera.getPosition()
                + " hit=" + (mc.hitResult == null ? "null" : mc.hitResult.getType() + "@" + mc.hitResult.getLocation()));
        return true;
    }

    private static void verifyHandPriority(Minecraft mc) {
        // Synchronous client-only eligibility checks on the actual visible
        // console. Restore both stacks before any tick or gameplay request.
        ItemStack main = mc.player.getMainHandItem(), off = mc.player.getOffhandItem();
        try {
            float[] eligible = com.caleb.unlockedcamera.client.AddonInteractionAim.angles(mc);
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            mc.player.setItemInHand(InteractionHand.OFF_HAND, main.copy());
            boolean emptyMain = com.caleb.unlockedcamera.client.AddonInteractionAim.angles(mc) == null;
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.BOW));
            boolean projectileMain = com.caleb.unlockedcamera.client.AddonInteractionAim.angles(mc) == null;
            log.accept("AEROWORKS_MODULE HAND_PRIORITY " + (eligible != null && emptyMain && projectileMain ? "PASS" : "FAIL")
                    + " mainModuleEligible=" + (eligible != null) + " emptyMainOffhandModuleIgnored=" + emptyMain
                    + " mainBowOffhandModuleIgnored=" + projectileMain + " scope=client-helper-no-gameplay-request");
            handControlsDone = true;
            if (eligible == null || !emptyMain || !projectileMain) throw new IllegalStateException("AeroWorks main-hand eligibility control failed");
        } finally {
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, main);
            mc.player.setItemInHand(InteractionHand.OFF_HAND, off);
        }
    }

    private static void pose(Minecraft mc) throws Exception {
        boolean shoulder = mode() == 1, free = mode() == 2;
        ClientConfig.TOGGLE_MOD.set(mode() != 0); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        field(UnlockedCameraClient.class, "active").setBoolean(null, shoulder);
        field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4); field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
        field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
        mc.options.setCameraType(shoulder ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        Vec3 eye = mc.player.getEyePosition(), origin = shoulder ? eye.add(.95, 0, -4) : eye;
        Vec3 direction = point.subtract(origin).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        bodyYaw = mode() == 0 ? yaw : yaw + 90; bodyPitch = mode() == 0 ? pitch : 0;
        body(mc, bodyYaw, bodyPitch); freelookKey.setDown(free);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, free ? yaw - bodyYaw : 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, free ? pitch - bodyPitch : 0);
        cameraPosition.invoke(mc.gameRenderer.getMainCamera(), origin); cameraRotation.invoke(mc.gameRenderer.getMainCamera(), yaw, pitch, 0f);
        // Keep the interaction hold's next Pre-tick input aligned with the
        // explicit fixture camera, including when the client is unfocused.
        if (regression) mc.gameRenderer.pick(1);
    }
    private static Object nearest(Object console, Vec3 from, Vec3 to) throws Exception {
        return removal() ? consoleClass.getMethod("nearestOccupiedMount", Vec3.class, Vec3.class).invoke(console, from, to)
                : consoleClass.getMethod("nearestFreeMount", Vec3.class, Vec3.class, moduleTypeClass).invoke(console, from, to, moduleType);
    }
    private static boolean chooseVisibleFixtureAim(Minecraft mc, Object console) throws Exception {
        Vec3 center = point;
        // Pedal sockets lie close to the desk floor and can be hidden by its leg.
        // Select an ordinary visible click within the addon's actual mount radius;
        // reuse this identical world-space point for both camera candidates.
        double[][] offsets = {{0,0,0}, {0,.125,0}, {0,.25,0}, {.125,0,0}, {-.125,0,0},
                {0,0,.125}, {0,0,-.125}, {.125,.125,0}, {-.125,.125,0}, {0,.125,.125}, {0,.125,-.125}};
        for (int i = 0; i < offsets.length; i++) {
            point = center.add(offsets[i][0], offsets[i][1], offsets[i][2]); pose(mc);
            Vec3 eye = mc.player.getEyePosition();
            Object mount = nearest(console, eye, eye.add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange())));
            if (!expectedRoot(mount)) continue;
            mc.gameRenderer.pick(1);
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && TARGET.equals(hit.getBlockPos())) {
                fixtureAimPoints.put(caseKey(), point);
                log.accept("AEROWORKS_MODULE FIXTURE_AIM " + label() + " socket=" + socket + " point=" + point + " sampled=" + (i + 1));
                return true;
            }
        }
        point = center; return false;
    }
    private static boolean expectedRoot(Object target) throws Exception {
        return targetSocket(target) == socket && target != null && target.getClass().getMethod("subPath").invoke(target) == null;
    }
    private static ModuleState moduleState(Object console) throws Exception {
        StringBuilder modules = new StringBuilder();
        int count = 0; String expected = "none";
        if (consoleClass.isInstance(console)) {
            int size = (int) consoleClass.getMethod("socketCount").invoke(console);
            for (int i = 0; i < size; i++) {
                Object module = consoleClass.getMethod("module", int.class).invoke(console, i);
                if (module == null) continue; count++;
                ItemStack stack = (ItemStack) module.getClass().getMethod("toStack").invoke(module);
                String moduleId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (i == socket) expected = moduleId; modules.append(i).append('=').append(moduleId).append(';');
            }
        }
        return new ModuleState(modules.toString(), expected, count);
    }
    private static Observation observe(ServerPlayer player, int token) throws Exception {
        Object console = snapshot.level.getBlockEntity(TARGET);
        ModuleState state = moduleState(console);
        int returned = 0; if (removal()) for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i); if (stack.is(items.get(itemIndex()))) returned += stack.getCount();
        }
        Vec3 eye = player.getEyePosition();
        Object serverMount = consoleClass.isInstance(console) ? nearest(console, eye, eye.add(player.getViewVector(1).scale(player.blockInteractionRange()))) : null;
        boolean ready = field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(player.connection) == null;
        boolean validState = snapshot.level.getBlockState(TARGET).equals(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(consoleId())).defaultBlockState());
        boolean validHeld = ItemStack.matches(player.getMainHandItem(), new ItemStack(removal() ? item("create:wrench") : items.get(itemIndex()), 1))
                && player.getOffhandItem().isEmpty() && player.getInventory().selected == 0;
        return new Observation(token, ready, player.canInteractWithBlock(TARGET, 0), state.modules, state.expectedItem, state.count, returned,
                targetText(serverMount), player.getYRot(), player.getXRot(), snapshot.level.getBlockState(TARGET).toString(), validState, validHeld,
                player.getYHeadRot(), player.tickCount, eye, player.getViewVector(1));
    }
    private static void requestAck(Minecraft mc) { ++token; ack = null; mc.player.connection.sendCommand(ACK + " " + token); }
    private static void finishCase(Minecraft mc, boolean success, String detail) throws Exception {
        String key = caseKey();
        if (mode() == 0) baselines.put(key, success);
        boolean positive = baselines.getOrDefault(key, false);
        String status = mode() != 0 && !positive ? "INCONCLUSIVE" : success ? "PASS" : "FAIL";
        if (status.equals("INCONCLUSIVE")) limited++; else { checks++; if (!success) failures++; }
        log.accept("AEROWORKS_MODULE " + status + " " + label() + " positiveControl=" + positive + " serverEffect=" + success
                + " clientResult=" + clientResult + " expectedSocket=" + socket + " cameraMount=" + cameraMount + " bodyMount=" + bodyMount
                + " cameraReferenceValid=" + cameraValid + " cameraTarget=" + hitDescription + " " + detail);
        next(mc);
    }
    private static void inconclusive(Minecraft mc, String reason) throws Exception {
        limited++; log.accept("AEROWORKS_MODULE INCONCLUSIVE " + labelSafe() + " " + reason); next(mc);
    }
    private static void next(Minecraft mc) throws Exception {
        // Keep the case identity and fixture unchanged until every earlier request
        // and ACK has crossed the server packet stream, including timeout paths.
        advancing = true;
        task = QaPacketBarrier.send(mc, player -> {});
    }
    private static void cleanupEntities() {
        List<Entity> remove = new ArrayList<>();
        for (Entity entity : snapshot.level.getAllEntities()) if (entity != null && !originals.contains(entity.getUUID()) && entity.getBoundingBox().intersects(AREA)) remove.add(entity);
        for (Entity entity : remove) if (!entity.isRemoved()) entity.discard();
    }
    private static void restore(Minecraft mc, String status) throws Exception {
        restoring = true; advancing = false; restorePhase = 0; restoreObservation = null; age = caseAge = 0;
        for (var entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        ClientConfig.ENABLE_FREELOOK.set(oldFreelookEnabled); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        freelookKey.setDown(oldFreelook); mc.options.keyShift.setDown(oldShift); mc.player.setShiftKeyDown(oldShift); mc.player.input.shiftKeyDown = oldShift;
        mc.options.setCameraType(oldType); mc.options.pauseOnLostFocus = oldPause;
        if (snapshot != null) { mc.player.getInventory().selected = snapshot.slot; body(mc, snapshot.yaw, snapshot.pitch); }
        // An ordered callback drains even a request whose client call threw before
        // the normal per-case ACK could be sent. It is never cancelled.
        task = QaPacketBarrier.send(mc, p -> {
            try {
                if (snapshot == null) return;
                if (fixtureReady) for (var entry : blocks.entrySet()) snapshot.level.setBlock(entry.getKey(), entry.getValue(), 3);
                cleanupEntities();
                p.getInventory().clearContent(); for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i).copy());
                p.getInventory().selected = snapshot.slot; p.setGameMode(snapshot.mode); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
                p.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                p.containerMenu.broadcastChanges(); p.inventoryMenu.sendAllDataToRemote();
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        log.accept("AEROWORKS_MODULE " + status + " checks=" + checks + " failures=" + failures + " limited=" + limited + " restoration=pending");
    }
    private static void body(Minecraft mc, float yaw, float pitch) throws Exception {
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setYHeadRot(yaw); mc.player.yHeadRotO = yaw;
        mc.player.setYBodyRot(yaw); mc.player.yBodyRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object value = rotation.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, value); field(camera.getClass(), "playerRotation").set(camera, value);
    }
    private static int rawIndex() { return review ? reviewCases.get(index) : index; }
    private static int mode() { return rawIndex() % 3; }
    private static boolean removal() { return (rawIndex() / 3) % 2 == 1; }
    private static String operation() { return removal() ? "remove" : "attach"; }
    private static int itemIndex() { return rawIndex() / (CONSOLES.length * 6); }
    private static String id() { return BuiltInRegistries.ITEM.getKey(items.get(itemIndex())).toString(); }
    private static String consoleId() { return CONSOLES[(rawIndex() / 6) % CONSOLES.length]; }
    private static String label() { return id() + " " + consoleId() + " " + operation() + " " + MODES[mode()]
            + (review ? " repeat=" + ((index / 2) % 3 + 1) : ""); }
    private static String caseKey() { return id() + "/" + consoleId() + "/" + operation(); }
    private static String labelSafe() { return index < total() ? label() : "restoring"; }
    private static int total() { return review ? reviewCases.size() : items.size() * CONSOLES.length * 6; }
    private static void selectReviewCases(java.nio.file.Path results) throws Exception {
        List<String> lines = java.nio.file.Files.readAllLines(results);
        int start = -1; boolean completed = false, restored = false;
        Map<String, String> controls = new LinkedHashMap<>(), candidates = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("AEROWORKS_MODULE START ")) start = i;
        if (start < 0) throw new IllegalStateException("No original AeroWorks module run");
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("AEROWORKS_MODULE DONE ")) completed = true;
            if (line.startsWith("AEROWORKS_MODULE RESTORED ")) restored = true;
            String[] words = line.split(" ");
            if (words.length < 6 || !words[0].equals("AEROWORKS_MODULE")
                    || !(words[1].equals("PASS") || words[1].equals("FAIL") || words[1].equals("INCONCLUSIVE"))) continue;
            String key = words[2] + "/" + words[3] + "/" + words[4];
            if (words[5].equals("first_person")) controls.put(key, words[1]);
            if (words[5].equals("freelook")) candidates.put(key, words[1]);
        }
        if (!completed || !restored) throw new IllegalStateException("Original AeroWorks sweep must finish and restore before review");
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) for (int consoleIndex = 0; consoleIndex < CONSOLES.length; consoleIndex++)
            for (int operation = 0; operation < 2; operation++) {
                String key = BuiltInRegistries.ITEM.getKey(items.get(itemIndex)) + "/" + CONSOLES[consoleIndex] + "/" + (operation == 0 ? "attach" : "remove");
                if (!"PASS".equals(controls.get(key)) || !candidates.containsKey(key) || "PASS".equals(candidates.get(key))) continue;
                int raw = itemIndex * CONSOLES.length * 6 + consoleIndex * 6 + operation * 3;
                for (int repeat = 0; repeat < 3; repeat++) { reviewCases.add(raw); reviewCases.add(raw + 2); }
            }
    }
    private static Item item(String id) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
    private static int targetSocket(Object target) throws Exception { return target == null ? -1 : (int) target.getClass().getMethod("socket").invoke(target); }
    private static String targetText(Object target) throws Exception { return target == null ? "none" : targetSocket(target) + "/" + target.getClass().getMethod("subPath").invoke(target); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void verifyRestoration(Minecraft mc) throws Exception {
        if (snapshot == null) throw new IllegalStateException("No saved snapshot to verify");
        mc.player.getInventory().selected = snapshot.slot; body(mc, snapshot.yaw, snapshot.pitch);
        if (age > 1200) throw new IllegalStateException("Restore state/teleport acknowledgement did not converge");
        if (mc.player.position().distanceToSqr(snapshot.position) > .0004 || !inventoryMatches(mc.player.getInventory())) return;
        if (restorePhase == 0) {
            restoreObservation = null; restorePhase = 1;
            task = QaPacketBarrier.send(mc, p -> {
                try {
                    int slots = inventoryDifferences(p.getInventory()), blockDifferences = 0, entities = 0;
                    for (var entry : blocks.entrySet()) if (!snapshot.level.getBlockState(entry.getKey()).equals(entry.getValue())) blockDifferences++;
                    for (Entity e : snapshot.level.getAllEntities()) if (e != null && !originals.contains(e.getUUID()) && e.getBoundingBox().intersects(AREA)) entities++;
                    boolean state = slots == 0 && blockDifferences == 0 && entities == 0 && p.getInventory().selected == snapshot.slot
                            && p.gameMode.getGameModeForPlayer() == snapshot.mode && p.getAbilities().flying == snapshot.flying
                            && p.position().distanceToSqr(snapshot.position) < .0004;
                    boolean pose = Math.abs(Mth.wrapDegrees(p.getYRot() - snapshot.yaw)) < .05 && Math.abs(p.getXRot() - snapshot.pitch) < .05;
                    boolean ready = field(net.minecraft.server.network.ServerGamePacketListenerImpl.class, "awaitingPositionFromClient").get(p.connection) == null;
                    restoreObservation = new RestoreObservation(state, pose, ready, slots, blockDifferences, entities, p.position(), p.getYRot(), p.getXRot());
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            return;
        }
        if (restoreObservation == null) return;
        if (!restoreObservation.teleportReady || !restoreObservation.pose) { restorePhase = 0; return; }
        boolean okay = restoreObservation.state && restoreObservation.pose && restoreObservation.teleportReady;
        log.accept("AEROWORKS_MODULE " + (okay ? "RESTORED" : "NOT_RESTORED") + " checks=" + checks + " failures=" + failures + " limited=" + limited
                + " stateRestored=" + restoreObservation.state + " poseRestored=" + restoreObservation.pose
                + " teleportAcknowledged=" + restoreObservation.teleportReady + " differentInventorySlots=" + restoreObservation.slots
                + " differentBlocks=" + restoreObservation.blocks + " remainingFixtureEntities=" + restoreObservation.entities);
        running = false;
    }
    private static boolean inventoryMatches(net.minecraft.world.entity.player.Inventory inventory) { return inventoryDifferences(inventory) == 0; }
    private static int inventoryDifferences(net.minecraft.world.entity.player.Inventory inventory) {
        int differences = 0;
        for (int i = 0; i < snapshot.inventory.size(); i++) if (!ItemStack.matches(snapshot.inventory.get(i), inventory.getItem(i))) differences++;
        return differences;
    }
    private static CompletableFuture<?> server(Minecraft mc, ServerAction action) {
        var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        server.execute(() -> {
            Throwable failure = null;
            try { action.run(server.getPlayerList().getPlayer(uuid)); }
            catch (Throwable error) { failure = error; }
            finally { if (failure == null) completed.complete(null); else completed.completeExceptionally(failure); }
        });
        return completed;
    }
}
