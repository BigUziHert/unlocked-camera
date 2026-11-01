package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Actual bucket packet followed by ordinary Pre-tick hold/body restoration. */
public final class BucketHoldRegressionProbe {
    private static final Vec3 PLAYER = new Vec3(1710.5, 172, 190.5);
    private static final BlockPos SOURCE = new BlockPos(1709, 173, 193);
    private static final String[] CASES = {"existing_offhand_bow", "no_hold", "new_mainhand_bow_before_next_pre", "held_first_person_freelook"};
    private static boolean running, registered, restoring, aborting, readySent, nextPoseStable, postUsePoseStable;
    private static int index, phase, age, preSerial, requestPre, failures;
    private static volatile Snapshot snapshot;
    private static volatile Observation before, immediate, next, later;
    private static CompletableFuture<Void> pending, immediateAck, nextAck;
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static Method position, rotation, drop;
    private static Map<Field, Object> savedFields;
    private static CameraType oldType;
    private static KeyMapping freeKey;
    private static boolean oldKey, oldToggle, oldShoulder, oldFree, oldKeep, oldPause;
    private static double oldAmount, oldMax;
    private static Vec3 oldCamera;
    private static float oldCameraYaw, oldCameraPitch, oldCameraRoll;
    private static float oldClientYaw, oldClientPitch, oldClientHead;
    private static Vec3 oldVelocity;
    private static boolean oldSprinting, oldOnGround;
    private static float[] nearAim, farAim, nextCache;
    private static String useResult, postUseCache, preUseDiagnostics, nextDiagnostics;
    private static long setupTick;
    private record Snapshot(ServerLevel level, Vec3 pos, float yaw, float pitch, GameType mode, boolean flying,
                            int selected, List<ItemStack> inventory, Map<BlockPos, BlockState> blocks) {}
    private record Observation(long tick, float yaw, float pitch, float head, boolean source, int waterBuckets,
                               String main, String off, int selected, boolean flying, boolean teleportReady) {
        String describe() { return "tick=" + tick + ";body=" + yaw + "," + pitch + ";head=" + head + ";source=" + source
                + ";waterBuckets=" + waterBuckets + ";main=" + main + ";off=" + off + ";selected=" + selected + ";flying=" + flying + ";teleportReady=" + teleportReady; }
    }
    private BucketHoldRegressionProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || mc.player == null || !mc.player.isAlive() || mc.player.isPassenger() || mc.player.isUsingItem()
                || mc.getSingleplayerServer() == null || mc.screen != null
                || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Bucket hold regression requires idle healthy disposable singleplayer");
        log = output;
        pending = immediateAck = nextAck = null; before = immediate = next = later = null;
        nearAim = farAim = nextCache = null; setupTick = 0; readySent = false;
        preUseDiagnostics = postUseCache = nextDiagnostics = useResult = "";
        nextPoseStable = postUsePoseStable = false;
        Path directory = Path.of("qa", "bucket-hold-regression", "run-" + System.currentTimeMillis()); Files.createDirectories(directory);
        writer = Files.newBufferedWriter(directory.resolve("outcomes.tsv"));
        row("case", "status", "near_bucket_aim", "far_continuous_aim", "preflight", "use_result", "immediate_server",
                "next_pre_cache", "next_pre_server", "later_server", "before_client_diagnostics", "post_use_client_diagnostics", "next_pre_client_diagnostics", "limits");
        savedFields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "freelookYaw", "freelookPitch", "shoulderSide", "shoulderOffset", "shoulderClearance", "collisionCap"}) {
            Field field = field(name); savedFields.put(field, field.get(null));
        }
        freeKey = (KeyMapping) field("FREELOOK_KEY").get(null); oldKey = freeKey.isDown();
        oldType = mc.options.getCameraType(); oldPause = mc.options.pauseOnLostFocus;
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldFree = ClientConfig.ENABLE_FREELOOK.get(); oldKeep = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        Camera camera = mc.gameRenderer.getMainCamera(); oldCamera = camera.getPosition();
        oldCameraYaw = camera.getYRot(); oldCameraPitch = camera.getXRot(); oldCameraRoll = camera.getRoll();
        oldClientYaw = mc.player.getYRot(); oldClientPitch = mc.player.getXRot(); oldClientHead = mc.player.getYHeadRot();
        oldVelocity = mc.player.getDeltaMovement(); oldSprinting = mc.player.isSprinting(); oldOnGround = mc.player.onGround();
        position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true);
        rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); rotation.setAccessible(true);
        drop = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); drop.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BucketHoldRegressionProbe::prePose);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BucketHoldRegressionProbe::preSample);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BucketHoldRegressionProbe::post);
            registered = true;
        }
        index = phase = age = preSerial = failures = 0; requestPre = -1; snapshot = null;
        restoring = aborting = false; running = true; mc.options.pauseOnLostFocus = false;
        pending = server(mc, p -> {
            QaPacketBarrier.install(mc.getSingleplayerServer());
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            for (BlockPos mutable : BlockPos.betweenClosed(1700, 170, 184, 1720, 177, 203)) {
                BlockPos pos = mutable.immutable();
                if (p.serverLevel().getBlockEntity(pos) != null) throw new IllegalStateException("Existing block entity in hold fixture " + pos);
                blocks.put(pos, p.serverLevel().getBlockState(pos));
            }
            List<ItemStack> inventory = new ArrayList<>();
            for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) inventory.add(p.getInventory().getItem(slot).copy());
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getAbilities().flying, p.getInventory().selected, inventory, blocks);
        });
        log.accept("BUCKET_HOLD START cases=4 file=" + directory.resolve("outcomes.tsv")
                + " action=actual_gameMode_useItem;camera=coherent_natural_negative_X_shoulder;observations=ordered_packet_ack,next_Pre,12_server_ticks");
    }

    private static void prePose(ClientTickEvent.Pre ignored) {
        if (!running || restoring || aborting || phase == 0) return;
        try { preSerial++; pose(Minecraft.getInstance()); }
        catch (Throwable error) { fail(error); }
    }
    private static void preSample(ClientTickEvent.Pre ignored) {
        if (!running || restoring || aborting || phase != 3 || requestPre < 0 || preSerial <= requestPre || nextAck != null) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            nextCache = copy(UnlockedCameraClient.continuousAimAngles()); nextDiagnostics = diagnostics(mc);
            nextPoseStable = poseStable(mc);
            nextAck = QaPacketBarrier.send(mc, p -> next = observe(p));
        } catch (Throwable error) { fail(error); }
    }
    private static void post(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("QA disconnected");
            if (aborting && !restoring) {
                if (pending != null && !pending.isDone()) return;
                if (immediateAck != null && !immediateAck.isDone()) return;
                if (nextAck != null && !nextAck.isDone()) return;
                restore(mc); return;
            }
            if (++age > 1800) throw new IllegalStateException("Hold fixture timeout case=" + name() + " phase=" + phase
                    + " position=" + mc.player.position() + " flying=" + mc.player.getAbilities().flying + " diagnostics=" + diagnostics(mc));
            if (restoring) { finishRestore(mc); return; }
            if (phase != 0) pose(mc);
            switch (phase) {
                case 0 -> { if (done(pending)) prepare(mc); }
                case 1 -> {
                    if (!done(pending)) { waiting(mc, "server_setup_or_preflight_barrier"); return; }
                    boolean held = mc.player.getMainHandItem().is(Items.BUCKET)
                            && (index == 0 ? mc.player.getOffhandItem().is(Items.BOW) : mc.player.getOffhandItem().isEmpty());
                    if (age < 8) return;
                    if (!held || !mc.player.getAbilities().flying || mc.player.position().distanceToSqr(PLAYER) > .001 || !mc.level.getFluidState(SOURCE).isSource()) {
                        waiting(mc, "client_fixture held=" + held + ";source=" + mc.level.getFluidState(SOURCE)); return;
                    }
                    float[] cached = UnlockedCameraClient.continuousAimAngles();
                    if ((index == 0 || index == 3) != (cached != null)) { waiting(mc, "expected_hold_presence cache=" + Arrays.toString(cached)); return; }
                    if (!readySent) {
                        readySent = true; pending = QaPacketBarrier.send(mc, p -> before = observe(p)); return;
                    }
                    if (before == null || !before.teleportReady || !before.flying || before.tick - setupTick < 6 || !before.source || before.waterBuckets != 0
                            || !before.main.equals("minecraft:bucket") || before.selected != 0
                            || !(index == 0 ? before.off.equals("minecraft:bow") : before.off.equals("minecraft:air"))) {
                        waiting(mc, "server_fixture before=" + (before == null ? "null" : before.describe())); readySent = false; return;
                    }
                    BlockHitResult sourceHit = UnlockedCameraClient.crosshairBlockPick(mc.player, mc.player.blockInteractionRange(), ClipContext.Fluid.SOURCE_ONLY);
                    if (sourceHit == null || sourceHit.getType() != HitResult.Type.BLOCK || !sourceHit.getBlockPos().equals(SOURCE)) {
                        waiting(mc, "source_ray=" + (sourceHit == null ? "null" : sourceHit.getType() + ":" + sourceHit.getBlockPos())); readySent = false; return;
                    }
                    nearAim = UnlockedCameraClient.bucketHitAimAngles(mc.player, sourceHit);
                    farAim = UnlockedCameraClient.crosshairAimAngles();
                    float[] initialExpected = index == 0 || index == 3 ? farAim : new float[]{0, 0};
                    float[] lastSent = (float[]) field("lastSentAim").get(null);
                    boolean initialSent = index == 0 || index == 3 ? lastSent != null && farAim != null && angular(lastSent, farAim) < .05 : lastSent == null;
                    if (nearAim == null || farAim == null || angleError(before, initialExpected) > .5
                            || !initialSent || !poseStable(mc) || index != 3 && angular(nearAim, farAim) < 5) {
                        waiting(mc, "aim_readiness near=" + Arrays.toString(nearAim) + ";far=" + Arrays.toString(farAim)
                                + ";initialSent=" + initialSent + ";poseStable=" + poseStable(mc) + ";before=" + before.describe());
                        readySent = false; return;
                    }
                    Vec3 camera = mc.gameRenderer.getMainCamera().getPosition(); var look = mc.gameRenderer.getMainCamera().getLookVector();
                    BlockHitResult farClip = mc.level.clip(new ClipContext(camera, camera.add(new Vec3(look.x(), look.y(), look.z()).scale(64)),
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                    if (farClip.getType() != HitResult.Type.MISS) throw new IllegalStateException("Hold fixture has solid backdrop " + farClip.getBlockPos());
                    preUseDiagnostics = diagnostics(mc); requestPre = preSerial;
                    useResult = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).toString();
                    postUseCache = diagnostics(mc); postUsePoseStable = poseStable(mc);
                    immediateAck = QaPacketBarrier.send(mc, p -> immediate = observe(p));
                    if (index == 2) {
                        // Real hotbar selection starts a new hold after the use
                        // packet but before the next Pre, without editing caches.
                        mc.player.getInventory().selected = 1;
                        mc.player.connection.send(new ServerboundSetCarriedItemPacket(1));
                    }
                    nextAck = null; phase = 3; age = 0;
                    log.accept("BUCKET_HOLD REQUEST case=" + name() + " near=" + Arrays.toString(nearAim) + " far=" + Arrays.toString(farAim)
                            + " use=" + useResult + " before=" + before.describe() + " client=" + preUseDiagnostics);
                }
                case 3 -> {
                    if (!done(immediateAck) || !done(nextAck)) return;
                    if (mc.getSingleplayerServer().overworld().getGameTime() - next.tick < 12) return;
                    pending = QaPacketBarrier.send(mc, p -> later = observe(p)); phase = 4; age = 0;
                }
                case 4 -> {
                    if (!done(pending)) return;
                    float[] expected = index == 1 ? new float[]{0, 0} : farAim;
                    boolean action = immediate != null && !immediate.source && immediate.waterBuckets > 0 && angleError(immediate, nearAim) < .5;
                    boolean cache = (index == 1 ? nextCache == null : nextCache != null && angular(nextCache, farAim) < .5)
                            && postUsePoseStable && nextPoseStable && poseStable(mc);
                    boolean nextAligned = angleError(next, expected) < .5;
                    boolean settled = angleError(later, expected) < .5 && Math.abs(Mth.wrapDegrees(later.head - expected[0])) < .5;
                    String status = !action ? "INVALID_USE_FIXTURE" : !cache ? "INVALID_CONTINUOUS_FIXTURE"
                            : nextAligned && settled ? "PASS" : "STALE_SERVER_ROTATION_AFTER_BUCKET";
                    if (!status.equals("PASS")) failures++;
                    row(name(), status, Arrays.toString(nearAim), Arrays.toString(farAim), before.describe(), useResult, immediate.describe(),
                            Arrays.toString(nextCache), next.describe(), later.describe(), preUseDiagnostics, postUseCache, nextDiagnostics,
                            "actual_bucket_use;creative_flying_fixture_prevents_fluid_push;next_Pre_body_required;head_checked_after_12_server_ticks;no_post_use_cache_mutation;freelook_near_far_coincident_control"
                                    + ";postUsePoseStable=" + postUsePoseStable + ";nextPrePoseStable=" + nextPoseStable + ";laterPoseStable=" + poseStable(mc));
                    writer.flush(); log.accept("BUCKET_HOLD RESULT case=" + name() + " status=" + status + " next=" + next.describe() + " later=" + later.describe());
                    if (++index == CASES.length) restore(mc); else prepare(mc);
                }
                default -> throw new IllegalStateException("Unexpected hold phase " + phase);
            }
        } catch (Throwable error) { fail(error); }
    }

    private static void prepare(Minecraft mc) throws Exception {
        drop.invoke(null); mc.gameMode.releaseUsingItem(mc.player);
        mc.player.getInventory().selected = 0;
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(0));
        before = immediate = next = later = null; nextAck = immediateAck = null; requestPre = -1; readySent = false;
        nextCache = null; preUseDiagnostics = postUseCache = nextDiagnostics = "";
        pending = server(mc, p -> {
            if (p.serverLevel() != snapshot.level) throw new IllegalStateException("Hold fixture changed dimension");
            for (BlockPos pos : snapshot.blocks.keySet()) {
                boolean playerHole = Math.abs(pos.getX() - 1710) <= 1 && Math.abs(pos.getZ() - 190) <= 1;
                BlockState state = pos.getY() <= 171 || pos.getY() == 172 && !playerHole ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                if (snapshot.level.getBlockState(pos) != state) snapshot.level.setBlock(pos, state, 3);
            }
            // Keep the stationary flying player off the ground. LocalPlayer
            // otherwise disables creative flight when standing on a floor.
            snapshot.level.setBlock(BlockPos.containing(PLAYER).below(), Blocks.AIR.defaultBlockState(), 3);
            snapshot.level.setBlock(SOURCE, Blocks.WATER.defaultBlockState(), 3);
            p.stopUsingItem(); p.stopRiding(); p.closeContainer(); p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true; p.onUpdateAbilities(); p.getInventory().clearContent(); p.getInventory().selected = 0;
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
            if (index == 0) p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BOW));
            if (index == 2) p.getInventory().setItem(1, new ItemStack(Items.BOW));
            p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z); p.setDeltaMovement(Vec3.ZERO); p.setOnGround(false);
            p.setYRot(0); p.setXRot(0); p.setYHeadRot(0); p.containerMenu.broadcastFullState(); setupTick = p.serverLevel().getGameTime();
        });
        phase = 1; age = 0;
    }
    private static void pose(Minecraft mc) throws Exception {
        if (mc.player == null) return;
        boolean free = index == 3;
        ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        field("active").setBoolean(null, !free); field("targetDistance").setFloat(null, 4); field("smoothedDistance").setFloat(null, 4);
        field("shoulderSide").setInt(null, 1); field("shoulderOffset").setFloat(null, .95f);
        field("shoulderClearance").setFloat(null, .95f); field("collisionCap").setFloat(null, 4);
        float yaw = free ? (float) Math.toDegrees(Math.atan2(1, 2.5)) : 0;
        field("freelookYaw").setFloat(null, yaw); field("freelookPitch").setFloat(null, 0); freeKey.setDown(free);
        mc.options.setCameraType(free ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        mc.player.setYRot(0); mc.player.yRotO = 0; mc.player.setXRot(0); mc.player.xRotO = 0;
        mc.player.setYHeadRot(0); mc.player.yHeadRotO = 0; mc.player.setSprinting(false); mc.player.setDeltaMovement(Vec3.ZERO); mc.player.setOnGround(false);
        Vec3 eye = mc.player.getEyePosition(); position.invoke(mc.gameRenderer.getMainCamera(), free ? eye : eye.add(-.95, 0, -4));
        rotation.invoke(mc.gameRenderer.getMainCamera(), yaw, 0f, 0f);
    }
    private static Observation observe(ServerPlayer p) {
        int water = 0;
        for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) if (p.getInventory().getItem(slot).is(Items.WATER_BUCKET)) water += p.getInventory().getItem(slot).getCount();
        return new Observation(p.serverLevel().getGameTime(), p.getYRot(), p.getXRot(), p.getYHeadRot(), p.serverLevel().getFluidState(SOURCE).isSource(),
                water, BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString(), BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).toString(),
                p.getInventory().selected, p.getAbilities().flying, teleportReady(p));
    }
    private static void restore(Minecraft mc) throws Exception {
        restoring = true; age = 0; drop.invoke(null); mc.gameMode.releaseUsingItem(mc.player);
        // Stand down aim while verifying the server restore. Restoring the old
        // active camera/held item now could legitimately stamp another angle.
        ClientConfig.TOGGLE_MOD.set(false); field("active").setBoolean(null, false);
        field("freelookYaw").setFloat(null, 0); field("freelookPitch").setFloat(null, 0);
        freeKey.setDown(false); mc.options.setCameraType(CameraType.FIRST_PERSON);
        pending = server(mc, p -> {
            if (snapshot == null) return;
            p.stopUsingItem(); p.stopRiding(); p.closeContainer();
            for (var entry : snapshot.blocks.entrySet()) snapshot.level.setBlock(entry.getKey(), entry.getValue(), 3);
            p.getInventory().clearContent(); for (int slot = 0; slot < snapshot.inventory.size(); slot++) p.getInventory().setItem(slot, snapshot.inventory.get(slot));
            p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
            p.teleportTo(snapshot.level, snapshot.pos.x, snapshot.pos.y, snapshot.pos.z, snapshot.yaw, snapshot.pitch); p.containerMenu.broadcastFullState();
        });
        phase = 90;
        log.accept("BUCKET_HOLD " + (aborting ? "ABORT" : "DONE") + " cases=" + Math.min(index, CASES.length) + " failures=" + failures + " restoration=pending");
    }
    private static void finishRestore(Minecraft mc) throws Exception {
        if (!done(pending)) return;
        if (phase == 90) {
            if (snapshot != null && mc.player.position().distanceToSqr(snapshot.pos) > .01) return;
            if (snapshot != null) {
                mc.player.getInventory().selected = snapshot.selected;
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(snapshot.selected));
            }
            pending = QaPacketBarrier.send(mc, p -> {
                if (snapshot == null) return;
                if (!teleportReady(p) || p.position().distanceToSqr(snapshot.pos) > .01) throw new IllegalStateException("Restore pose not acknowledged");
                if (p.serverLevel() != snapshot.level || p.gameMode.getGameModeForPlayer() != snapshot.mode
                        || p.getAbilities().flying != snapshot.flying || p.getInventory().selected != snapshot.selected
                        || Math.abs(Mth.wrapDegrees(p.getYRot() - snapshot.yaw)) > .01 || Math.abs(p.getXRot() - snapshot.pitch) > .01)
                    throw new IllegalStateException("Restore dimension/mode/flight/slot/rotation differs");
                for (int slot = 0; slot < snapshot.inventory.size(); slot++)
                    if (!ItemStack.matches(p.getInventory().getItem(slot), snapshot.inventory.get(slot))) throw new IllegalStateException("Restore inventory differs at " + slot);
                for (var entry : snapshot.blocks.entrySet()) if (snapshot.level.getBlockState(entry.getKey()) != entry.getValue()) throw new IllegalStateException("Restore block differs " + entry.getKey());
            }); phase = 91; return;
        }
        if (snapshot != null) for (int slot = 0; slot < snapshot.inventory.size(); slot++)
            if (!ItemStack.matches(mc.player.getInventory().getItem(slot), snapshot.inventory.get(slot))) return;
        for (var entry : savedFields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.ENABLE_FREELOOK.set(oldFree); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeep);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        freeKey.setDown(oldKey); mc.options.setCameraType(oldType); mc.options.pauseOnLostFocus = oldPause;
        mc.player.setYRot(oldClientYaw); mc.player.yRotO = oldClientYaw; mc.player.setXRot(oldClientPitch); mc.player.xRotO = oldClientPitch;
        mc.player.setYHeadRot(oldClientHead); mc.player.yHeadRotO = oldClientHead; mc.player.setDeltaMovement(oldVelocity); mc.player.setSprinting(oldSprinting); mc.player.setOnGround(oldOnGround);
        position.invoke(mc.gameRenderer.getMainCamera(), oldCamera); rotation.invoke(mc.gameRenderer.getMainCamera(), oldCameraYaw, oldCameraPitch, oldCameraRoll);
        writer.close(); running = false; log.accept("BUCKET_HOLD RESTORED inventory_and_blocks_verified=true dimension_mode_flight_slot_rotation_verified=true teleportAcknowledged=true");
    }
    private static void fail(Throwable error) {
        if (restoring) {
            if (pending != null && !pending.isDone()) { aborting = true; age = 0; log.accept("BUCKET_HOLD WAIT_RESTORE_DRAIN " + error); return; }
            running = false; try { writer.close(); } catch (Exception ignored) {}
            log.accept("BUCKET_HOLD ABORT NOT_RESTORED " + error); return;
        }
        if (aborting) return;
        log.accept("BUCKET_HOLD ERROR case=" + name() + " phase=" + phase + " " + error); failures++; aborting = true;
    }
    private static String diagnostics(Minecraft mc) throws Exception {
        return "clientPre=" + preSerial + ";position=" + mc.player.position() + ";flying=" + mc.player.getAbilities().flying
                + ";body=" + mc.player.getYRot() + "," + mc.player.getXRot()
                + ";camera=" + mc.gameRenderer.getMainCamera().getPosition() + ";cameraYaw=" + mc.gameRenderer.getMainCamera().getYRot()
                + ";cache=" + Arrays.toString(UnlockedCameraClient.continuousAimAngles()) + ";lastSent=" + Arrays.toString((float[]) field("lastSentAim").get(null))
                + ";heldTicks=" + field("heldAimTicks").getLong(null) + ";lastSendTick=" + field("lastAimSendTick").getLong(null)
                + ";resync=" + field("aimResyncRequested").getBoolean(null) + ";selected=" + mc.player.getInventory().selected
                + ";main=" + mc.player.getMainHandItem() + ";off=" + mc.player.getOffhandItem();
    }
    private static void waiting(Minecraft mc, String reason) throws Exception {
        if (age % 100 == 0) log.accept("BUCKET_HOLD WAIT_READY case=" + name() + " phase=" + phase + " age=" + age + " reason=" + reason
                + " position=" + mc.player.position() + " flying=" + mc.player.getAbilities().flying + " onGround=" + mc.player.onGround()
                + " diagnostics=" + diagnostics(mc));
    }
    private static boolean poseStable(Minecraft mc) {
        boolean free = index == 3; float yaw = free ? (float) Math.toDegrees(Math.atan2(1, 2.5)) : 0;
        Vec3 expected = free ? mc.player.getEyePosition() : mc.player.getEyePosition().add(-.95, 0, -4);
        Camera camera = mc.gameRenderer.getMainCamera();
        return mc.player.position().distanceToSqr(PLAYER) < .001 && Math.abs(mc.player.getYRot()) < .001 && Math.abs(mc.player.getXRot()) < .001
                && camera.getPosition().distanceToSqr(expected) < .0001 && Math.abs(Mth.wrapDegrees(camera.getYRot() - yaw)) < .001
                && Math.abs(camera.getXRot()) < .001 && freeKey.isDown() == free;
    }
    private static double angleError(Observation value, float[] expected) { return Math.max(Math.abs(Mth.wrapDegrees(value.yaw - expected[0])), Math.abs(value.pitch - expected[1])); }
    private static double angular(float[] left, float[] right) { return Math.max(Math.abs(Mth.wrapDegrees(left[0] - right[0])), Math.abs(left[1] - right[1])); }
    private static float[] copy(float[] value) { return value == null ? null : value.clone(); }
    private static String name() { return index < CASES.length ? CASES[index] : "complete"; }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static boolean done(CompletableFuture<Void> future) { if (future == null || !future.isDone()) return false; future.join(); return true; }
    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var id = mc.player.getUUID(); return mc.getSingleplayerServer().submit(() -> action.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id)));
    }
    private static boolean teleportReady(ServerPlayer player) {
        try { Field f = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient"); f.setAccessible(true); return f.get(player.connection) == null; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void row(Object... cells) throws Exception {
        for (int i = 0; i < cells.length; i++) { if (i > 0) writer.write('\t'); writer.write(String.valueOf(cells[i]).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')); }
        writer.newLine();
    }
}
