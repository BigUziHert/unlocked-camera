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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Every default block: normal first-person vs the actual FIRST_PERSON freelook input mode. */
public final class BlockFreelookProbe {
    private static final Vec3 PLAYER = new Vec3(1742.5, 150, 190.5);
    private static final BlockPos TARGET = new BlockPos(1742, 150, 193);
    private static final BoundingBox BOX = new BoundingBox(1738, 149, 185, 1746, 156, 198);
    private static final AABB AREA = new AABB(1738, 149, 185, 1747, 157, 199);
    private static final Path FILE = Path.of("qa", "block-freelook-interactions.tsv");
    private static final String ACK = "unlockedcamera_qa_block_freelook";
    private static boolean running, registered, oldToggle, oldFreeEnabled, oldFreeKey, oldPause, oldKeepDirection;
    private static double oldFreeLimit;
    private static int index, mode, phase, age, token, passed, limited, differences, reused;
    private static volatile int eventCount;
    private static volatile String eventState = "none", eventCancellation = "none";
    private static volatile Snapshot saved;
    private static volatile Observation prepared, acknowledgement;
    private static CompletableFuture<Void> task;
    private static Observation before, firstBefore, firstAfter;
    private static String pickStatus, use, hitPoint, firstPick, firstUse, firstHit;
    private static List<String> ids;
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static Map<Field, Object> fields;
    private static CameraType oldType;
    private static KeyMapping freeKey;
    private static Method position, rotation, freelookHeld;
    private static Field pendingTeleport;
    private static String modeEvidence = "not_sent", firstModeEvidence;
    private static float poseCameraYaw, poseCameraPitch, poseBodyYaw, poseBodyPitch;
    private static Vec3 poseDirection;
    private record SavedBlock(BlockState state, CompoundTag data) {}
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode,
                            int selected, List<ItemStack> inventory, Map<BlockPos, SavedBlock> blocks, Set<UUID> entities) {}
    private record Observation(int token, boolean ready, String state, String menu, String vehicle, String blockEntity,
                               int events, String usedState, String cancellation, String serverAim, String error) {
        String signature() { return state + "|" + menu + "|" + vehicle + "|" + blockEntity; }
    }
    private BlockFreelookProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || BlockInteractionProbe.isRunning() || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Freelook block probe requires an idle disposable integrated world");
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game")) && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Restricted to disposable QA copies");
        log = output;
        Files.createDirectories(FILE.getParent()); ensureIdentity();
        Set<String> completed = new HashSet<>();
        if (Files.exists(FILE)) for (String line : Files.readAllLines(FILE)) {
            String[] cells = line.split("\t", -1);
            if (cells.length > 2 && !cells[0].equals("block") && !cells[1].equals("ERROR")) completed.add(cells[0]);
        }
        ids = BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).filter(id -> !completed.contains(id)).sorted().toList();
        reused = completed.size();
        boolean append = Files.exists(FILE);
        writer = Files.newBufferedWriter(FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!append) writer.write("block\tstatus\tfirst_pick\tfirst_use\tfirst_before\tfirst_after\tfirst_server_events\tfirst_event_state\tfirst_event_cancellation\tfreelook_pick\tfreelook_use\tfreelook_before\tfreelook_after\tfreelook_server_events\tfreelook_event_state\tfreelook_event_cancellation\tfirst_hit\tfreelook_hit\tfirst_mode\tfreelook_mode\tlimits\n");
        fields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field f = field(name); fields.put(f, f.get(null));
        }
        freeKey = (KeyMapping) field("FREELOOK_KEY").get(null);
        oldFreeKey = freeKey.isDown(); oldFreeEnabled = ClientConfig.ENABLE_FREELOOK.get();
        oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        oldFreeLimit = ClientConfig.FREELOOK_YAW_LIMIT.get();
        oldType = mc.options.getCameraType(); oldToggle = ClientConfig.TOGGLE_MOD.get(); oldPause = mc.options.pauseOnLostFocus;
        position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true);
        rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); rotation.setAccessible(true);
        freelookHeld = UnlockedCameraClient.class.getDeclaredMethod("isFreelookHeld", Minecraft.class); freelookHeld.setAccessible(true);
        pendingTeleport = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient"); pendingTeleport.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(BlockFreelookProbe::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, BlockFreelookProbe::useBeginning);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, BlockFreelookProbe::useEnding);
            registered = true;
        }
        index = mode = phase = age = token = passed = limited = differences = 0; saved = null; running = true;
        mc.setScreen(null); mc.options.pauseOnLostFocus = false;
        log.accept("BLOCK_FREELOOK START pending=" + ids.size() + " alreadyRecorded=" + reused
                + " candidate=actual_FIRST_PERSON_freelook bodyDelta=90 keyHeld=true offset=false file=" + FILE);
        task = server(mc, p -> {
            List<ItemStack> inventory = new ArrayList<>();
            for (int n = 0; n < p.getInventory().getContainerSize(); n++) inventory.add(p.getInventory().getItem(n).copy());
            Map<BlockPos, SavedBlock> blocks = new LinkedHashMap<>();
            for (BlockPos mutable : BlockPos.betweenClosed(1738, 149, 185, 1746, 156, 198)) {
                BlockPos pos = mutable.immutable(); BlockEntity be = p.serverLevel().getBlockEntity(pos);
                blocks.put(pos, new SavedBlock(p.serverLevel().getBlockState(pos), be == null ? null : be.saveWithFullMetadata(p.registryAccess())));
            }
            Set<UUID> entities = new HashSet<>();
            for (Entity e : p.serverLevel().getEntities(p, AREA)) entities.add(e.getUUID());
            saved = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getInventory().selected, inventory, blocks, entities);
            mc.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(ACK)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        if (running && mc.player != null && player.getUUID().equals(mc.player.getUUID()))
                            acknowledgement = observe(player, IntegerArgumentType.getInteger(context, "token"));
                        return 1;
                    })));
            p.stopRiding(); p.closeContainer(); p.setGameMode(GameType.CREATIVE);
            clearFixture(p); p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
        });
    }

    private static boolean ours(PlayerInteractEvent.RightClickBlock event) {
        return running && !event.getLevel().isClientSide() && event.getPos().equals(TARGET)
                && Minecraft.getInstance().player != null && event.getEntity().getUUID().equals(Minecraft.getInstance().player.getUUID());
    }
    private static void useBeginning(PlayerInteractEvent.RightClickBlock event) {
        if (ours(event)) { eventCount++; eventState = event.getLevel().getBlockState(TARGET).toString(); }
    }
    private static void useEnding(PlayerInteractEvent.RightClickBlock event) {
        if (ours(event)) eventCancellation = event.isCanceled() + ":" + event.getCancellationResult();
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.level == null) throw new IllegalStateException("Disconnected");
            if (++age > 600) throw new IllegalStateException("Timeout phase=" + phase + " mode=" + mode + " block=" + id());
            if (mc.screen != null && mc.screen.isPauseScreen()) mc.setScreen(null);
            switch (phase) {
                case 0 -> {
                    if (!task.isDone() || age < 25) return;
                    task.join(); prepare(mc);
                }
                case 1 -> {
                    if (!task.isDone()) return;
                    task.join();
                    if (!mc.level.dimension().equals(saved.level.dimension())) throw new IllegalStateException("DIMENSION_CHANGED");
                    Observation state = prepared;
                    if (!state.error.isEmpty()) throw new IllegalStateException(state.error);
                    if (!state.ready || mc.player.position().distanceToSqr(PLAYER) > .01) {
                        request(mc); phase = 2; age = 0; return;
                    }
                    if (!mc.level.getBlockState(TARGET).toString().equals(state.state)) {
                        if (age < 8) return;
                        request(mc); phase = 2; age = 0; return;
                    }
                    beginPose(mc, state);
                }
                case 2 -> {
                    Observation state = acknowledgement;
                    if (state == null || state.token != token) return;
                    if (!state.error.isEmpty()) throw new IllegalStateException(state.error);
                    if (!state.ready || mc.player.position().distanceToSqr(PLAYER) > .01) {
                        if (age % 20 == 0) {
                            releaseHold(); mc.player.setDeltaMovement(Vec3.ZERO);
                            task = server(mc, p -> { p.setDeltaMovement(Vec3.ZERO); p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z); prepared = observe(p, -1); });
                            phase = 1; return;
                        }
                        request(mc); return;
                    }
                    if (!mc.level.getBlockState(TARGET).toString().equals(state.state)) { request(mc); return; }
                    beginPose(mc, state);
                }
                case 3 -> {
                    Observation after = acknowledgement;
                    if (after == null || after.token != token) return;
                    if (!after.error.isEmpty()) throw new IllegalStateException(after.error);
                    completeMode(mc, after);
                }
                case 4 -> {
                    // The real held-freelook aim cache is populated in Pre.
                    // Keep the pose through three complete ticks before use.
                    if (age < 3) return;
                    request(mc); phase = 5; age = 0;
                }
                case 5 -> {
                    Observation state = acknowledgement;
                    if (state == null || state.token != token) return;
                    if (!state.error.isEmpty()) throw new IllegalStateException(state.error);
                    if (!state.ready || mc.player.position().distanceToSqr(PLAYER) > .01)
                        throw new IllegalStateException("Fixture moved during held-pose preflight");
                    if (!mc.level.getBlockState(TARGET).toString().equals(state.state)) { request(mc); return; }
                    act(mc, state);
                }
                default -> throw new IllegalStateException("Bad phase " + phase);
            }
        } catch (Throwable error) {
            log.accept("BLOCK_FREELOOK ERROR block=" + id() + " mode=" + mode + " " + error);
            try {
                row(id(), "ERROR", "", "", "", "", "0", "", "", "", "", "", "", "0", "", "", "", "", "", "", clean(error));
                finish(mc, "ABORT");
            } catch (Throwable restore) { running = false; log.accept("BLOCK_FREELOOK RESTORE_ERROR " + restore); }
        }
    }

    private static void prepare(Minecraft mc) throws Exception {
        while (index < ids.size() && id().equals("copycats:wrapped_copycat")) {
            row(id(), "NEEDS_SPECIALIZED_FIXTURE", "NOT_RUN", "NOT_SENT", "", "", "0", "", "", "NOT_RUN", "NOT_SENT", "", "", "0", "", "", "", "", "", "", "internal_wrapper_default_block_entity_is_not_constructible");
            index++; limited++;
        }
        if (index >= ids.size()) { finish(mc, "DONE"); return; }
        releaseHold(); mc.player.closeContainer(); mc.setScreen(null);
        freeKey.setDown(false); field("freelookYaw").setFloat(null, 0); field("freelookPitch").setFloat(null, 0);
        mc.player.getInventory().clearContent(); mc.player.getInventory().selected = 0; mc.player.setDeltaMovement(Vec3.ZERO);
        String requested = id();
        task = server(mc, p -> {
            if (p.serverLevel() != saved.level) throw new IllegalStateException("DIMENSION_CHANGED");
            p.stopRiding(); p.closeContainer(); clearFixture(p);
            p.getInventory().clearContent(); p.getInventory().selected = 0; p.setDeltaMovement(Vec3.ZERO); p.resetFallDistance();
            // Most cases stay in place; avoid the original sweep's unnecessary
            // teleport race. Any needed recovery is explicitly acknowledged.
            if (p.position().distanceToSqr(PLAYER) > .001) p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
            p.setYRot(0); p.setXRot(0); p.containerMenu.broadcastChanges();
            BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(requested)).defaultBlockState();
            if (state.hasProperty(BlockStateProperties.ATTACH_FACE) && state.getValue(BlockStateProperties.ATTACH_FACE) == AttachFace.WALL
                    && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                saved.level.setBlock(TARGET.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()), Blocks.STONE.defaultBlockState(), 3);
            saved.level.setBlock(TARGET, state, 3);
            eventCount = 0; eventState = eventCancellation = "none";
            prepared = observe(p, -1);
        });
        phase = 1; age = 0;
    }

    private static void clearFixture(ServerPlayer p) {
        for (Entity e : saved.level.getEntities(p, AREA)) if (!saved.entities.contains(e.getUUID())) e.discard();
        saved.level.getBlockTicks().clearArea(BOX); saved.level.getFluidTicks().clearArea(BOX);
        for (BlockPos pos : saved.blocks.keySet()) {
            BlockState desired = pos.getY() == 149 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (saved.level.getBlockState(pos) != desired) saved.level.setBlock(pos, desired, 2);
        }
        for (Entity e : saved.level.getEntities(p, AREA)) if (!saved.entities.contains(e.getUUID())) e.discard();
    }

    private static void beginPose(Minecraft mc, Observation state) throws Exception {
        before = state; pickStatus = "NO_OUTLINE"; use = "NOT_SENT"; hitPoint = "none"; modeEvidence = "no_request";
        BlockState actual = mc.level.getBlockState(TARGET);
        if (!BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString().equals(id())) {
            pickStatus = "DEFAULT_REPLACED:" + actual; completeMode(mc, state); return;
        }
        var shape = actual.getShape(mc.level, TARGET, CollisionContext.of(mc.player));
        if (shape.isEmpty()) { completeMode(mc, state); return; }
        AABB largest = shape.toAabbs().stream().max(Comparator.comparingDouble(b -> b.getXsize() * b.getYsize() * b.getZsize())).orElseThrow();
        Vec3 point = largest.getCenter().add(TARGET.getX(), TARGET.getY(), TARGET.getZ());
        Vec3 eye = mc.player.getEyePosition(1), direction = point.subtract(eye).normalize();
        float cameraYaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90);
        float cameraPitch = (float) -Math.toDegrees(Math.asin(direction.y));
        float bodyYaw = mode == 0 ? cameraYaw : cameraYaw + 90f;
        float bodyPitch = mode == 0 ? cameraPitch : 0;
        mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw;
        mc.player.setXRot(bodyPitch); mc.player.xRotO = bodyPitch;
        ClientConfig.TOGGLE_MOD.set(mode != 0); ClientConfig.ENABLE_FREELOOK.set(true);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        ClientConfig.FREELOOK_YAW_LIMIT.set(90d);
        mc.options.setCameraType(CameraType.FIRST_PERSON); field("active").setBoolean(null, false);
        freeKey.setDown(mode != 0);
        field("freelookYaw").setFloat(null, 0); field("freelookPitch").setFloat(null, 0);
        if (mode != 0 && !UnlockedCameraClient.freelookMouseTurn(-600, cameraPitch / .15))
            throw new IllegalStateException("Freelook mouse-turn input hook did not consume the deflection");
        boolean held = (boolean) freelookHeld.invoke(null, mc);
        if (mode != 0 && !held) throw new IllegalStateException("Actual FIRST_PERSON freelook gate did not engage");
        poseCameraYaw = cameraYaw; poseCameraPitch = cameraPitch;
        poseBodyYaw = bodyYaw; poseBodyPitch = bodyPitch; poseDirection = direction;
        phase = 4; age = 0;
    }

    private static void act(Minecraft mc, Observation state) throws Exception {
        before = state;
        boolean held = (boolean) freelookHeld.invoke(null, mc);
        if (mode != 0 && (!held || Math.abs(mc.player.getYRot() - poseBodyYaw) > .01f
                || Math.abs(mc.player.getXRot() - poseBodyPitch) > .01f))
            throw new IllegalStateException("Held freelook pose changed before request");
        if (!BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(TARGET).getBlock()).toString().equals(id())) {
            pickStatus = "DEFAULT_REPLACED:" + mc.level.getBlockState(TARGET); completeMode(mc, state); return;
        }
        Vec3 eye = mc.player.getEyePosition(1);
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 oldPosition = camera.getPosition(); float oldYaw = camera.getYRot(), oldPitch = camera.getXRot(), oldRoll = camera.getRoll();
        try {
            position.invoke(camera, eye); rotation.invoke(camera, poseCameraYaw, poseCameraPitch, 0f);
            HitResult picked = mode == 0 ? mc.level.clip(new ClipContext(eye, eye.add(poseDirection.scale(mc.player.blockInteractionRange())),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player))
                    : UnlockedCameraClient.cameraRayPick(mc.player, mc.player.blockInteractionRange(), mc.player.entityInteractionRange(), 1);
            modeEvidence = "FIRST_PERSON;active=false;keyHeld=" + freeKey.isDown() + ";actualFreelookGate=" + held + ";inputHook=" + (mode != 0)
                    + ";bodyYaw=" + poseBodyYaw + ";cameraYaw=" + poseCameraYaw + ";bodyPitch=" + poseBodyPitch + ";cameraPitch=" + poseCameraPitch + ";posePreflightTicks=3";
            if (!(picked instanceof BlockHitResult hit) || picked.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(TARGET)) {
                pickStatus = picked == null ? "NULL" : picked.getType() + ":" + picked.getLocation();
                completeMode(mc, state); return;
            }
            pickStatus = "TARGET"; hitPoint = hit.getDirection() + ":" + hit.getLocation(); mc.hitResult = hit;
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(poseBodyYaw, poseBodyPitch, mc.player.onGround()));
            use = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).toString();
        } finally { position.invoke(camera, oldPosition); rotation.invoke(camera, oldYaw, oldPitch, oldRoll); }
        request(mc); phase = 3; age = 0;
    }

    private static void request(Minecraft mc) { acknowledgement = null; mc.player.connection.sendCommand(ACK + " " + ++token); }
    private static Observation observe(ServerPlayer p, int requested) {
        try {
            if (p.serverLevel() != saved.level) throw new IllegalStateException("DIMENSION_CHANGED");
            BlockEntity be = saved.level.getBlockEntity(TARGET);
            return new Observation(requested, pendingTeleport.get(p.connection) == null && p.position().distanceToSqr(PLAYER) < .01,
                    saved.level.getBlockState(TARGET).toString(), p.containerMenu == p.inventoryMenu ? "inventory" : p.containerMenu.getClass().getName(),
                    p.getVehicle() == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString(),
                    be == null ? "none" : be.getClass().getName(), eventCount, eventState, eventCancellation,
                    p.getYRot() + "," + p.getXRot() + ",head=" + p.getYHeadRot(), "");
        } catch (Throwable error) { return new Observation(requested, false, "", "", "", "", eventCount, eventState, eventCancellation, "unknown", error.toString()); }
    }

    private static void completeMode(Minecraft mc, Observation after) throws Exception {
        modeEvidence += ";serverBeforeAim=" + before.serverAim + ";serverAfterAim=" + after.serverAim;
        if (mode == 0) {
            firstBefore = before; firstAfter = after; firstPick = pickStatus; firstUse = use; firstHit = hitPoint; firstModeEvidence = modeEvidence;
            mode = 1;
        } else {
            int firstEvents = firstAfter.events - firstBefore.events, freeEvents = after.events - before.events;
            String status;
            if (!firstPick.equals("TARGET") || !pickStatus.equals("TARGET"))
                status = firstPick.equals("TARGET") && !pickStatus.startsWith("DEFAULT_REPLACED") && !pickStatus.equals("NO_OUTLINE") ? "TARGETING_DIFFERENCE_REVIEW" : "NEEDS_SPECIALIZED_FIXTURE";
            else if (!firstBefore.signature().equals(before.signature())) status = "UNEQUAL_STARTING_FIXTURE";
            else if (firstEvents == 0 || freeEvents == 0) status = "SERVER_ACTION_NOT_OBSERVED";
            else if (!firstAfter.usedState.equals(firstBefore.state) || !after.usedState.equals(before.state)) status = "FIXTURE_CHANGED_BEFORE_USE";
            else if (!firstAfter.signature().equals(after.signature())) status = "SERVER_DIFFERENCE_REVIEW";
            else if (!firstUse.equals(use) || !firstAfter.cancellation.equals(after.cancellation)) status = "MATCHED_SERVER_LOCAL_RETURN_DIFF";
            else status = firstAfter.signature().equals(firstBefore.signature()) ? "MATCHED_NO_OBSERVABLE_EFFECT" : "OBSERVED_PARITY_PASS";
            if (status.equals("OBSERVED_PARITY_PASS")) passed++;
            else if (status.endsWith("REVIEW") || status.equals("SERVER_ACTION_NOT_OBSERVED") || status.equals("MATCHED_SERVER_LOCAL_RETURN_DIFF")) differences++;
            else limited++;
            row(id(), status, firstPick, firstUse, firstBefore.signature(), firstAfter.signature(), firstEvents, firstAfter.usedState, firstAfter.cancellation,
                    pickStatus, use, before.signature(), after.signature(), freeEvents, after.usedState, after.cancellation, firstHit, hitPoint,
                    firstModeEvidence, modeEvidence, "default_state;empty_main_hand;actual_FP_freelook;body_delta90;fresh_support;teleport_ready;held_pose_preflight3ticks;ordered_server_ack;event_state_guard;offhand_fallback_and_custom_tools_untested");
            if (status.endsWith("REVIEW") || status.equals("SERVER_ACTION_NOT_OBSERVED")) log.accept("BLOCK_FREELOOK " + status + " " + id());
            index++; mode = 0;
            if (index % 64 == 0) { writer.flush(); log.accept("BLOCK_FREELOOK PROGRESS " + index + "/" + ids.size() + " pass=" + passed + " differences=" + differences + " limited=" + limited); }
        }
        prepare(mc);
    }

    private static void finish(Minecraft mc, String status) throws Exception {
        running = false; writer.close(); releaseHold(); mc.player.closeContainer(); mc.setScreen(null);
        freeKey.setDown(oldFreeKey); ClientConfig.ENABLE_FREELOOK.set(oldFreeEnabled); ClientConfig.TOGGLE_MOD.set(oldToggle);
        ClientConfig.FREELOOK_YAW_LIMIT.set(oldFreeLimit);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        for (Map.Entry<Field, Object> entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        mc.options.setCameraType(oldType); mc.options.pauseOnLostFocus = oldPause;
        Snapshot original = saved;
        if (original != null) server(mc, p -> {
            p.stopRiding(); p.closeContainer();
            for (Entity e : original.level.getEntities(p, AREA)) if (!original.entities.contains(e.getUUID())) e.discard();
            original.level.getBlockTicks().clearArea(BOX); original.level.getFluidTicks().clearArea(BOX);
            for (Map.Entry<BlockPos, SavedBlock> entry : original.blocks.entrySet()) {
                original.level.setBlock(entry.getKey(), entry.getValue().state, 3);
                BlockEntity be = original.level.getBlockEntity(entry.getKey());
                if (be != null && entry.getValue().data != null) be.loadWithComponents(entry.getValue().data, p.registryAccess());
            }
            for (int n = 0; n < original.inventory.size(); n++) p.getInventory().setItem(n, original.inventory.get(n));
            p.getInventory().selected = original.selected; p.setGameMode(original.mode);
            p.teleportTo(original.level, original.position.x, original.position.y, original.position.z, original.yaw, original.pitch);
            p.containerMenu.broadcastChanges();
        }).thenRun(() -> log.accept("BLOCK_FREELOOK RESTORED"));
        log.accept("BLOCK_FREELOOK " + status + " rows=" + index + " pendingAtStart=" + ids.size() + " alreadyRecorded=" + reused
                + " pass=" + passed + " differences=" + differences + " limited=" + limited);
    }

    private static void ensureIdentity() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Class<?> type : new Class<?>[]{BlockFreelookProbe.class, UnlockedCameraClient.class})
            try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (in == null) throw new IllegalStateException("Missing class bytes " + type);
                digest.update(in.readAllBytes());
            }
        String mods = net.neoforged.fml.ModList.get().getMods().stream().map(m -> m.getModId() + "=" + m.getVersion()).sorted().collect(java.util.stream.Collectors.joining("\n"));
        digest.update(mods.getBytes(StandardCharsets.UTF_8)); String identity = HexFormat.of().formatHex(digest.digest());
        Path marker = Path.of(FILE + ".identity");
        if (Files.exists(FILE) && (!Files.exists(marker) || !Files.readString(marker).trim().equals(identity))) {
            Path archive = Path.of("qa", "block-freelook-interactions-previous-" + System.currentTimeMillis() + ".tsv");
            Files.move(FILE, archive); log.accept("BLOCK_FREELOOK ARCHIVE stale_identity=" + archive);
        }
        Files.writeString(marker, identity);
    }
    private static void releaseHold() throws Exception {
        try {
            Class<?> manager = Class.forName("dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager");
            if ((boolean) manager.getMethod("isActive").invoke(null)) manager.getMethod("stop").invoke(null);
        } catch (ClassNotFoundException absent) { /* optional */ }
    }
    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer(); UUID player = mc.player.getUUID();
        return CompletableFuture.runAsync(() -> action.accept(server.getPlayerList().getPlayer(player)), server);
    }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static String id() { return ids != null && index < ids.size() ? ids.get(index) : "<complete>"; }
    private static String clean(Object value) { return String.valueOf(value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' '); }
    private static void row(Object... cells) throws Exception {
        for (int n = 0; n < cells.length; n++) { if (n > 0) writer.write('\t'); writer.write(clean(cells[n])); } writer.newLine();
    }
}
