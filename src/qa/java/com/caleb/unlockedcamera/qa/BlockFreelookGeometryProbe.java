package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.regex.Pattern;

/**
 * Exhaustive DEFAULT-STATE block targeting, plus paired empty-hand interactions.
 * This is deliberately not a claim about every powered machine, recipe, block
 * state, multiblock, tool action, or Sable plot. Compiled only with -PcameraQa.
 */
public final class BlockFreelookGeometryProbe {
    private static boolean manualRegistered;
    private static final Vec3 PLAYER = new Vec3(1742.5, 150, 190.5);
    private static final BlockPos TARGET = new BlockPos(1742, 150, 193);
    private static final AABB AREA = new AABB(1738, 149, 185, 1747, 157, 199);
    private static final Path ROOT = Path.of("qa");
    private static final String ACK_COMMAND = "unlockedcamera_qa_capture";
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static List<Block> blocks;
    private static List<BlockState> states;
    private static Map<Field, Object> cameraFields;
    private static CameraType previousType;
    private static boolean previousToggle, previousShoulder;
    private static double previousMax;
    private static volatile Snapshot snapshot;
    private static volatile Observation acknowledgement;
    private static CompletableFuture<Void> serverTask;
    private static String useResult, pickStatus, pickFace, pickLocation;
    private static boolean running, gameplay, allStates;
    private static int index, phase, waitTicks, token, mode, passed, failed, limited;
    private static Observation baseline;
    private static String baselinePick, baselineUse, baselineFace, baselineLocation;
    private static Set<UUID> fixtureEntities = Set.of();
    private static BlockState expectedState;

    private record SavedBlock(BlockState state, CompoundTag data) {}
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode,
                            int selected, List<ItemStack> inventory,
                            Map<BlockPos, SavedBlock> blocks) {}
    private record Observation(int token, String state, String menu, String vehicle,
                               String blockEntity, String error) {
        String signature() { return state + "|" + menu + "|" + vehicle + "|" + blockEntity; }
    }

    private BlockFreelookGeometryProbe() {}

    /** Ordered network acknowledgement after the actual UseItemOn packet. */
    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ACK_COMMAND)
                .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    Minecraft mc = Minecraft.getInstance();
                    int requested = IntegerArgumentType.getInteger(context, "token");
                    if (running && gameplay && mc.player != null && player.getUUID().equals(mc.player.getUUID())) {
                        acknowledgement = observe(player, requested);
                    }
                    return 1;
                })));
    }

    public static boolean isRunning() { return running; }

    /** All registry entries, three aiming modes; up to 128 entries per client tick. */
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (!manualRegistered) { NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick(Minecraft.getInstance())); manualRegistered = true; }
        begin(mc, output, false, "all", true);
    }

    /** All possible registered states, each in isolation, in three ray modes. */
    public static void runStates(Minecraft mc, Consumer<String> output) throws Exception {
        begin(mc, output, false, "all", true);
    }

    /**
     * Actual request/ack pairs. Filter is a namespace regex, or "all". Completed
     * rows are resumed; delete/rename the evidence file explicitly for a rerun.
     */
    public static void start(Minecraft mc, Consumer<String> output, String filter) throws Exception {
        begin(mc, output, true, filter == null || filter.isBlank() ? "all" : filter, false);
    }

    private static void begin(Minecraft mc, Consumer<String> output, boolean interactions, String filter, boolean stateSweep) throws Exception {
        if (running) throw new IllegalStateException("Block probe already running");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Block probe needs the disposable integrated-server world");
        Path gamePath = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!gamePath.endsWith(Path.of("build", "modpack-test", "game"))
                && !gamePath.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Block probe is restricted to the two disposable QA copies");
        log = output;
        gameplay = interactions;
        allStates = stateSweep;
        Files.createDirectories(ROOT);
        Path file = ROOT.resolve(gameplay ? "block-freelook-geometry-interactions-unused.tsv" : allStates ? "block-state-freelook-targeting.tsv" : "block-freelook-geometry-default-unused.tsv");
        if (gameplay) ensureResumeIdentity(file);
        Set<String> complete = new HashSet<>();
        if (gameplay && Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                String[] row = line.split("\t", -1);
                if (row.length > 2 && !row[0].equals("block") && !row[1].equals("ERROR")) complete.add(row[0]);
            }
        }
        Pattern names = Pattern.compile(filter.equals("all") ? ".*" : filter);
        blocks = BuiltInRegistries.BLOCK.stream().filter(b -> names.matcher(BuiltInRegistries.BLOCK.getKey(b).getNamespace()).matches())
                .filter(b -> !complete.contains(BuiltInRegistries.BLOCK.getKey(b).toString()))
                .sorted(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString())).toList();
        states = allStates ? blocks.stream().flatMap(b -> b.getStateDefinition().getPossibleStates().stream()).toList() : null;
        boolean append = gameplay && Files.exists(file);
        writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
        if (!append) row(gameplay
                ? "block\tstatus\tbaseline_pick\tbaseline_use\tbaseline_observation\tshoulder_pick\tshoulder_use\tshoulder_observation\tbaseline_face\tshoulder_face\tbaseline_hit\tshoulder_hit\tlimits"
                : "block\tstatus\tfirst_person\tfreelook_left\tfreelook_right\tstate_count\tlimits");
        previousType = mc.options.getCameraType();
        previousToggle = ClientConfig.TOGGLE_MOD.get();
        previousShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        previousMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        cameraFields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(name); cameraFields.put(field, field.get(null));
        }
        index = phase = mode = passed = failed = limited = 0;
        waitTicks = 0;
        snapshot = null;
        running = true;
        mc.setScreen(null);
        mc.options.pauseOnLostFocus = false;
        log.accept("BLOCK_" + label() + " START entries=" + total()
                + " alreadyRecorded=" + complete.size() + " filter=" + filter + " file=" + file);
        serverTask = onServer(mc, player -> {
            List<ItemStack> inventory = new ArrayList<>();
            for (int n = 0; n < player.getInventory().getContainerSize(); n++) inventory.add(player.getInventory().getItem(n).copy());
            Map<BlockPos, SavedBlock> saved = new LinkedHashMap<>();
            for (BlockPos mutable : BlockPos.betweenClosed(1738, 149, 185, 1746, 156, 198)) {
                BlockPos pos = mutable.immutable();
                BlockEntity be = player.serverLevel().getBlockEntity(pos);
                saved.put(pos, new SavedBlock(player.serverLevel().getBlockState(pos),
                        be == null ? null : be.saveWithFullMetadata(player.registryAccess())));
            }
            snapshot = new Snapshot(player.serverLevel(), player.position(), player.getYRot(), player.getXRot(),
                    player.gameMode.getGameModeForPlayer(), player.getInventory().selected, inventory, saved);
            for (BlockPos pos : saved.keySet()) player.serverLevel().setBlock(pos,
                    pos.getY() == 149 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            fixtureEntities = new HashSet<>();
            for (Entity entity : player.serverLevel().getEntities(player, AREA)) fixtureEntities.add(entity.getUUID());
            player.stopRiding(); player.closeContainer();
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().clearContent(); player.getInventory().selected = 0;
            player.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
            player.setYRot(0); player.setXRot(0);
            player.containerMenu.broadcastChanges();
        });
    }

    public static void tick(Minecraft mc) {
        if (!running) return;
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            log.accept("BLOCK_FREELOOK_GEOMETRY_PROBE ABORT disconnected; fixture remains in disposable copy");
            running = false; closeWriter(); return;
        }
        try {
            if (++waitTicks > 400) throw new IllegalStateException("Timeout phase=" + phase + " block=" + currentId());
            if (phase == 0) {
                if (!serverTask.isDone()) return;
                serverTask.join();
                if (mc.player.position().distanceToSqr(PLAYER) > .01 || !mc.level.hasChunkAt(TARGET)) return;
                if (waitTicks < 25) return; // Initial chunk and teleport delivery only.
                phase = 1; waitTicks = 0;
            }
            if (index >= total()) { finish(mc, "DONE"); return; }
            if (!gameplay) {
                for (int n = 0; n < 128 && index < total(); n++, index++) geometry(mc, allStates ? states.get(index) : blocks.get(index).defaultBlockState());
                waitTicks = 0;
                if (index % 256 == 0 || index == total()) progress();
                return;
            }
            switch (phase) {
                case 1 -> prepareInteraction(mc);
                case 2 -> {
                    if (!serverTask.isDone()) return;
                    serverTask.join();
                    if (!mc.level.dimension().equals(snapshot.level.dimension()))
                        throw new IllegalStateException("DIMENSION_CHANGED requires specialized block fixture");
                    BlockState actual = mc.level.getBlockState(TARGET);
                    if (actual != expectedState) {
                        if (waitTicks < 20) return;
                        // Unsupported default states can self-remove (crops, multipart blocks).
                        useResult = "NOT_SENT"; pickStatus = "DEFAULT_STATE_UNSTABLE:" + actual; pickFace = pickLocation = "none";
                        acknowledgement = null; ++token;
                        mc.player.connection.sendCommand(ACK_COMMAND + " " + token);
                        phase = 3; waitTicks = 0; return;
                    }
                    sendInteraction(mc);
                }
                case 3 -> {
                    // Editing screens must not pause the integrated server
                    // before its ordered observation command is processed.
                    if (mc.screen != null && mc.screen.isPauseScreen()) mc.setScreen(null);
                    Observation observed = acknowledgement;
                    if (observed == null || observed.token != token) return;
                    if (observed.error.startsWith("DIMENSION_CHANGED")) throw new IllegalStateException(observed.error);
                    if (mode == 0) {
                        baseline = observed; baselinePick = pickStatus; baselineUse = useResult;
                        baselineFace = pickFace; baselineLocation = pickLocation;
                        mode = 1; phase = 1; waitTicks = 0;
                    } else {
                        completeInteraction(observed);
                        index++; mode = 0; phase = 1; waitTicks = 0;
                        if (index % 64 == 0 || index == blocks.size()) progress();
                    }
                    // The immutable observation is already authoritative: its
                    // command ran after the use packet on the same connection.
                    // Queue the next fixture immediately instead of burning a
                    // separate client tick in phase 1. Phase 2 still waits for
                    // server completion, client block sync and player position.
                    if (index < total()) prepareInteraction(mc);
                    else finish(mc, "DONE");
                }
                default -> throw new IllegalStateException("Bad block phase " + phase);
            }
        } catch (Throwable error) {
            log.accept("BLOCK_FREELOOK_GEOMETRY_PROBE ERROR block=" + currentId() + " " + error);
            error.printStackTrace();
            if (gameplay && phase != 0 && index < total()) {
                String failure = clean(error);
                try {
                    row(currentId() + "\t" + (failure.contains("DIMENSION_CHANGED") ? "NEEDS_SPECIALIZED_FIXTURE" : "ERROR")
                            + "\t" + clean(baselinePick) + "\t" + clean(baselineUse) + "\t"
                            + "\t" + clean(pickStatus) + "\t" + clean(useResult) + "\t"
                            + "\t" + clean(baselineFace) + "\t" + clean(pickFace)
                            + "\t" + clean(baselineLocation) + "\t" + clean(pickLocation) + "\t" + failure);
                } catch (Exception writeError) { log.accept("BLOCK_FREELOOK_GEOMETRY_PROBE WRITE_ERROR " + writeError); }
            }
            try { finish(mc, "ABORT"); } catch (Throwable restore) { log.accept("BLOCK_FREELOOK_GEOMETRY_PROBE RESTORE_ERROR " + restore); }
        }
    }

    private static void geometry(Minecraft mc, BlockState fixtureState) throws Exception {
        Block block = fixtureState.getBlock();
        String id = allStates ? fixtureState.toString() : BuiltInRegistries.BLOCK.getKey(block).toString();
        BlockState before = mc.level.getBlockState(TARGET);
        try {
            // This registered wrapper is an internal contextual block. Its
            // raw state instantiates Create's BE with an unsupported type;
            // the first runtime run recorded that exception explicitly.
            if (BuiltInRegistries.BLOCK.getKey(block).toString().equals("copycats:wrapped_copycat")) {
                limited++;
                row(id + "\tNEEDS_SPECIALIZED_FIXTURE\tNOT_RUN\tNOT_RUN\tNOT_RUN\t"
                        + block.getStateDefinition().getPossibleStates().size()
                        + "\tinternal_wrapped_copycat_requires_context;raw_default_BE_rejected_in_initial_run");
                return;
            }
            mc.level.setBlock(TARGET, fixtureState, 0);
            String[] results = new String[3];
            for (int view = 0; view < 3; view++) results[view] = pick(mc, view).status;
            boolean noShape = results[0].equals("NO_OUTLINE");
            boolean ok = results[0].equals("TARGET") && results[1].equals("TARGET") && results[2].equals("TARGET");
            boolean explained = results[0].equals("TARGET")
                    && java.util.Arrays.stream(results).allMatch(r -> r.equals("TARGET") || r.startsWith("REFERENCE_OCCLUDED:") || r.startsWith("REFERENCE_OUT_OF_REACH:"));
            String status = ok ? "TARGETING_PASS" : noShape ? "NO_OUTLINE" : !results[0].equals("TARGET") ? "BASELINE_MISS"
                    : explained ? "REFERENCE_OCCLUDED_OR_RANGE" : "TARGETING_FAIL";
            if (ok) passed++; else if (status.equals("TARGETING_FAIL")) failed++; else limited++;
            row(id + "\t" + status + "\t" + results[0] + "\t" + results[1] + "\t" + results[2]
                    + "\t" + block.getStateDefinition().getPossibleStates().size()
                    + "\t" + (allStates ? "isolated_state" : "default_state_only") + ";real_FIRST_PERSON_freelook_input;key_held;active_false;keepDirection_false;body_camera_deflection_75;direct_camera_pose;no_action_or_machine_function");
        } catch (Throwable error) {
            failed++;
            row(id + "\tERROR\t" + clean(error) + "\t\t\t" + block.getStateDefinition().getPossibleStates().size() + "\tfixture_or_compatibility_error");
        } finally { mc.level.setBlock(TARGET, before, 0); }
    }

    private record Pick(String status, BlockHitResult hit, Vec3 origin, float yaw, float pitch) {}

    private static Pick pick(Minecraft mc, int view) throws Exception {
        BlockState state = mc.level.getBlockState(TARGET);
        var shape = state.getShape(mc.level, TARGET, CollisionContext.of(mc.player));
        if (shape.isEmpty()) return new Pick("NO_OUTLINE", null, null, 0, 0);
        // A point inside the largest individual outline box also works for
        // hollow rings, chairs, trapdoors and position-offset plants.
        AABB largest = shape.toAabbs().stream().max(Comparator.comparingDouble(b -> b.getXsize() * b.getYsize() * b.getZsize())).orElseThrow();
        Vec3 point = largest.getCenter().add(TARGET.getX(), TARGET.getY(), TARGET.getZ());
        Vec3 eye = mc.player.getEyePosition(1);
        Vec3 origin = eye; // Real freelook is first-person: the eye stays fixed while the body differs.
        Vec3 direction = point.subtract(origin).normalize();
        float yaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.asin(direction.y));
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 oldPos = camera.getPosition();
        float oldYaw = camera.getYRot(), oldPitch = camera.getXRot(), oldRoll = camera.getRoll();
        CameraType oldType = mc.options.getCameraType();
        float oldBodyYaw = mc.player.getYRot(), oldBodyPitch = mc.player.getXRot();
        float oldBodyYawO = mc.player.yRotO, oldBodyPitchO = mc.player.xRotO;
        KeyMapping freeKey = (KeyMapping) field("FREELOOK_KEY").get(null);
        boolean oldKey = freeKey.isDown(), oldEnabled = ClientConfig.ENABLE_FREELOOK.get();
        boolean oldKeep = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        double oldLimit = ClientConfig.FREELOOK_YAW_LIMIT.get();
        Object oldActive = field("active").get(null), oldDistance = field("targetDistance").get(null);
        Object oldFreelookYaw = field("freelookYaw").get(null), oldFreelookPitch = field("freelookPitch").get(null);
        boolean oldToggle = ClientConfig.TOGGLE_MOD.get(), oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        double oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        position.setAccessible(true); rotation.setAccessible(true);
        try {
            ClientConfig.TOGGLE_MOD.set(view != 0);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field("active").set(null, false); field("targetDistance").set(null, 4f);
            ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
            ClientConfig.FREELOOK_YAW_LIMIT.set(90d);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            freeKey.setDown(view != 0);
            float deflection = view == 1 ? -75 : 75;
            mc.player.setYRot(view == 0 ? yaw : yaw - deflection); mc.player.yRotO = mc.player.getYRot();
            mc.player.setXRot(view == 0 ? pitch : 0); mc.player.xRotO = mc.player.getXRot();
            field("freelookYaw").set(null, 0f); field("freelookPitch").set(null, 0f);
            if (view != 0 && !UnlockedCameraClient.freelookMouseTurn(deflection / .15, pitch / .15))
                throw new IllegalStateException("Actual first-person freelook input was not consumed");
            position.invoke(camera, origin); rotation.invoke(camera, yaw, pitch, 0f);
            HitResult hit = view == 0
                    ? mc.level.clip(new ClipContext(eye, eye.add(direction.scale(mc.player.blockInteractionRange())),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player))
                    : UnlockedCameraClient.cameraRayPick(mc.player, mc.player.blockInteractionRange(), mc.player.entityInteractionRange(), 1);
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(TARGET))
                return new Pick("TARGET", block, origin, yaw, pitch);
            if (view != 0) {
                // Independent level clip at the player's depth on this exact
                // rendered ray. Different view origins can encounter the
                // fixture floor before low/overhanging shapes. Only reclassify
                // when the real mod result matches this independent oracle.
                var forward = camera.getLookVector();
                Vec3 ray = new Vec3(forward.x(), forward.y(), forward.z());
                double depth = Math.max(0, eye.subtract(origin).dot(ray));
                Vec3 start = origin.add(ray.scale(depth));
                double reach = mc.player.blockInteractionRange();
                Vec3 end = origin.add(ray.scale(Math.max(reach, mc.player.entityInteractionRange()) + origin.distanceTo(eye)));
                BlockHitResult reference = mc.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (reference.getType() == HitResult.Type.BLOCK && !reference.getBlockPos().equals(TARGET)
                        && hit instanceof BlockHitResult actual && hit.getType() == HitResult.Type.BLOCK
                        && actual.getBlockPos().equals(reference.getBlockPos())
                        && actual.getLocation().distanceToSqr(reference.getLocation()) < 1.0E-8) {
                    return new Pick("REFERENCE_OCCLUDED:" + reference.getBlockPos(), null, origin, yaw, pitch);
                }
                if (reference.getType() == HitResult.Type.BLOCK && reference.getLocation().distanceToSqr(eye) > reach * reach
                        && hit != null && hit.getType() == HitResult.Type.MISS) {
                    return new Pick("REFERENCE_OUT_OF_REACH:" + reference.getBlockPos(), null, origin, yaw, pitch);
                }
            }
            return new Pick(hit == null ? "NULL" : hit.getType() + ":" + (hit instanceof BlockHitResult block ? block.getBlockPos() : hit.getLocation()), null, origin, yaw, pitch);
        } finally {
            ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
            ClientConfig.ENABLE_FREELOOK.set(oldEnabled); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeep);
            ClientConfig.FREELOOK_YAW_LIMIT.set(oldLimit); freeKey.setDown(oldKey);
            mc.player.setYRot(oldBodyYaw); mc.player.setXRot(oldBodyPitch);
            mc.player.yRotO = oldBodyYawO; mc.player.xRotO = oldBodyPitchO;
            field("active").set(null, oldActive); field("targetDistance").set(null, oldDistance);
            field("freelookYaw").set(null, oldFreelookYaw); field("freelookPitch").set(null, oldFreelookPitch);
            mc.options.setCameraType(oldType); position.invoke(camera, oldPos); rotation.invoke(camera, oldYaw, oldPitch, oldRoll);
        }
    }

    private static void prepareInteraction(Minecraft mc) throws Exception {
        if (currentId().equals("copycats:wrapped_copycat")) {
            limited++;
            row(currentId() + "\tNEEDS_SPECIALIZED_FIXTURE\tNOT_RUN\tNOT_SENT\t\tNOT_RUN\tNOT_SENT\t\tnone\tnone\tnone\tnone\tinternal_wrapped_copycat_requires_context;raw_default_BE_rejected_in_initial_run");
            index++; mode = 0; waitTicks = 0;
            return;
        }
        mc.player.closeContainer(); mc.setScreen(null);
        mc.player.getInventory().clearContent(); mc.player.getInventory().selected = 0;
        expectedState = blocks.get(index).defaultBlockState();
        serverTask = onServer(mc, player -> {
            if (player.serverLevel() != snapshot.level)
                throw new IllegalStateException("DIMENSION_CHANGED requires specialized block fixture");
            player.stopRiding(); player.closeContainer();
            for (Entity entity : player.serverLevel().getEntities(player, AREA))
                if (!fixtureEntities.contains(entity.getUUID())) entity.discard();
            player.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
            player.setYRot(0); player.setXRot(0);
            player.getInventory().clearContent(); player.getInventory().selected = 0;
            // Force a fresh block entity and state for each mode's comparison.
            player.serverLevel().setBlock(TARGET, Blocks.AIR.defaultBlockState(), 3);
            player.serverLevel().setBlock(TARGET, expectedState, 3);
            player.containerMenu.broadcastChanges();
        });
        phase = 2; waitTicks = 0;
    }

    private static void sendInteraction(Minecraft mc) throws Exception {
        if (mc.player.position().distanceToSqr(PLAYER) > .01) return;
        Pick picked = pick(mc, mode == 0 ? 0 : 1);
        pickStatus = picked.status;
        useResult = "NOT_SENT";
        pickFace = picked.hit == null ? "none" : picked.hit.getDirection().toString();
        pickLocation = picked.hit == null ? "none" : picked.hit.getLocation().toString();
        // Preserve actual selected hit, but the gameplay request is the real
        // vanilla client path and runs installed event/mixin compatibility.
        if (picked.hit != null) {
            ClientConfig.TOGGLE_MOD.set(mode != 0);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field("active").set(null, mode != 0); field("targetDistance").set(null, 4f);
            mc.options.setCameraType(mode == 0 ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 oldPosition = camera.getPosition();
            float oldYaw = camera.getYRot(), oldPitch = camera.getXRot(), oldRoll = camera.getRoll();
            Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
            Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
            position.setAccessible(true); rotation.setAccessible(true);
            try {
                position.invoke(camera, picked.origin); rotation.invoke(camera, picked.yaw, picked.pitch, 0f);
                // Baseline really faces its selected point. The shoulder case
                // keeps the body forward while the crosshair converges onto it.
                float bodyYaw = mode == 0 ? picked.yaw : 0;
                float bodyPitch = mode == 0 ? picked.pitch : 0;
                mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw;
                mc.player.setXRot(bodyPitch); mc.player.xRotO = bodyPitch;
                mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(bodyYaw, bodyPitch, mc.player.onGround()));
                mc.hitResult = picked.hit;
                useResult = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, picked.hit).toString();
            } finally {
                position.invoke(camera, oldPosition); rotation.invoke(camera, oldYaw, oldPitch, oldRoll);
            }
        }
        acknowledgement = null;
        ++token;
        mc.player.connection.sendCommand(ACK_COMMAND + " " + token);
        phase = 3; waitTicks = 0;
    }

    private static Observation observe(ServerPlayer player, int token) {
        try {
            if (snapshot != null && player.serverLevel() != snapshot.level)
                return new Observation(token, "", "", "", "",
                        "DIMENSION_CHANGED:" + player.serverLevel().dimension().location());
            BlockEntity entity = player.serverLevel().getBlockEntity(TARGET);
            // Class + menu/state/vehicle are stable observables. Full BE NBT
            // includes time-dependent counters and creates false mismatches.
            return new Observation(token, player.serverLevel().getBlockState(TARGET).toString(),
                    player.containerMenu == player.inventoryMenu ? "inventory" : player.containerMenu.getClass().getName(),
                    player.getVehicle() == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString(),
                    entity == null ? "none" : entity.getClass().getName(), "");
        } catch (Throwable error) { return new Observation(token, "", "", "", "", error.toString()); }
    }

    private static void completeInteraction(Observation observed) throws Exception {
        String status;
        if (!baseline.error.isEmpty() || !observed.error.isEmpty()) status = "ERROR";
        else if (!baselinePick.equals("TARGET")) status = "NEEDS_FIXTURE";
        else if (!pickStatus.equals("TARGET")) status = pickStatus.startsWith("REFERENCE_") ? "NEEDS_FIXTURE" : "TARGETING_FAIL";
        else if (!baseline.signature().equals(observed.signature()) || !baselineUse.equals(useResult))
            status = !baselineFace.equals(pickFace) ? "CONTEXT_DIFFERENCE" : "OBSERVATION_DIFF";
        else {
            boolean effect = !observed.state.equals(expectedState.toString()) || !observed.menu.equals("inventory") || !observed.vehicle.equals("none");
            status = effect ? "OBSERVED_PARITY_PASS" : "NO_OBSERVABLE_EFFECT";
        }
        if (status.endsWith("PASS")) passed++;
        else if (status.equals("TARGETING_FAIL") || status.equals("OBSERVATION_DIFF") || status.equals("ERROR")) failed++;
        else limited++;
        row(currentId() + "\t" + status + "\t" + clean(baselinePick) + "\t" + clean(baselineUse)
                + "\t" + clean(baseline.signature()) + "\t" + clean(pickStatus) + "\t" + clean(useResult)
                + "\t" + clean(observed.signature())
                + "\t" + clean(baselineFace) + "\t" + clean(pickFace) + "\t" + clean(baselineLocation) + "\t" + clean(pickLocation)
                + "\tdefault_state;empty_main_hand;one_shoulder;ordered_server_ack;observables=state_menu_mount_beclass;powered_tool_recipe_sublevel_actions_untested");
        if (status.equals("OBSERVATION_DIFF") || status.equals("TARGETING_FAIL"))
            log.accept("BLOCK_INTERACTIONS " + status + " " + currentId() + " first=" + baseline.signature() + " shoulder=" + observed.signature());
    }

    private static void progress() throws Exception {
        writer.flush();
        log.accept("BLOCK_" + label() + " PROGRESS " + index + "/" + total()
                + " pass=" + passed + " differencesOrErrors=" + failed + " limited=" + limited);
    }

    public static void stop(Minecraft mc) throws Exception { if (running) finish(mc, "STOPPED"); }

    private static void finish(Minecraft mc, String status) throws Exception {
        running = false;
        progress(); closeWriter();
        mc.player.closeContainer(); mc.setScreen(null);
        ClientConfig.TOGGLE_MOD.set(previousToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(previousShoulder);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(previousMax);
        for (Map.Entry<Field, Object> entry : cameraFields.entrySet()) entry.getKey().set(null, entry.getValue());
        mc.options.setCameraType(previousType);
        Snapshot original = snapshot;
        if (original != null) onServer(mc, player -> {
            player.stopRiding(); player.closeContainer();
            for (Entity entity : original.level.getEntities(player, AREA))
                if (!fixtureEntities.contains(entity.getUUID())) entity.discard();
            for (Map.Entry<BlockPos, SavedBlock> entry : original.blocks.entrySet()) {
                original.level.setBlock(entry.getKey(), entry.getValue().state, 3);
                BlockEntity be = original.level.getBlockEntity(entry.getKey());
                if (be != null && entry.getValue().data != null) be.loadWithComponents(entry.getValue().data, player.registryAccess());
            }
            for (int n = 0; n < original.inventory.size(); n++) player.getInventory().setItem(n, original.inventory.get(n));
            player.getInventory().selected = original.selected;
            player.setGameMode(original.mode);
            player.teleportTo(original.level, original.position.x, original.position.y, original.position.z, original.yaw, original.pitch);
            player.setYRot(original.yaw); player.setXRot(original.pitch);
            player.containerMenu.broadcastChanges();
        }).thenRun(() -> log.accept("BLOCK_" + label() + " RESTORED original dimension, fixture and player"));
        log.accept("BLOCK_" + label() + " " + status + " rows=" + index
                + " pass=" + passed + " differencesOrErrors=" + failed + " limited=" + limited);
    }

    private static CompletableFuture<Void> onServer(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        UUID playerId = mc.player.getUUID();
        return CompletableFuture.runAsync(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("QA player not found");
            action.accept(player);
        }, server);
    }

    private static String currentId() {
        return blocks != null && index < total() ? (allStates ? states.get(index).toString() : BuiltInRegistries.BLOCK.getKey(blocks.get(index)).toString()) : "<complete>";
    }
    private static int total() { return allStates ? states.size() : blocks.size(); }
    private static String label() { return gameplay ? "INTERACTIONS" : allStates ? "STATE_FREELOOK_TARGETING" : "TARGETING"; }
    private static void ensureResumeIdentity(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // Never combine evidence across a changed probe/camera build or mod set.
        for (Class<?> type : new Class<?>[]{BlockFreelookGeometryProbe.class, UnlockedCameraClient.class}) {
            try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (in == null) throw new IllegalStateException("Cannot fingerprint " + type);
                digest.update(in.readAllBytes());
            }
        }
        String mods = net.neoforged.fml.ModList.get().getMods().stream()
                .map(m -> m.getModId() + "=" + m.getVersion()).sorted().collect(java.util.stream.Collectors.joining("\n"));
        digest.update(mods.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String identity = HexFormat.of().formatHex(digest.digest());
        Path identityFile = ROOT.resolve("block-interactions-identity.txt");
        String existing = Files.exists(identityFile) ? Files.readString(identityFile).trim() : "";
        if (Files.exists(file) && !identity.equals(existing)) {
            Path archived = ROOT.resolve("block-interactions-previous-" + System.currentTimeMillis() + ".tsv");
            Files.move(file, archived);
            log.accept("BLOCK_INTERACTIONS archived stale or unidentified evidence=" + archived);
        }
        Files.writeString(identityFile, identity + System.lineSeparator());
    }
    private static Field field(String name) throws Exception {
        Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static String clean(Object value) { return String.valueOf(value).replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    private static void row(String text) throws Exception { writer.write(text); writer.newLine(); }
    private static void closeWriter() {
        if (writer != null) try { writer.close(); } catch (Exception error) { log.accept("BLOCK_FREELOOK_GEOMETRY_PROBE WRITE_ERROR " + error); }
        writer = null;
    }
}
