package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Contextual BucketItem use: actual POV-based action, independent client prediction/server observations. */
public final class BucketUseProbe {
    private static final Path BASE_ROOT = Path.of("qa", "bucket-use");
    private static Path ROOT = BASE_ROOT;
    private static boolean reviewing, handSmoke, openWater;
    private static int handIndex;
    private static String fixtureKind = "default_stack", settledClient = "";
    private static BlockPos expectedMobCell;
    private static final String[] MODES = {"first_person", "shoulder", "freelook"};
    private static final Vec3 PLAYER = new Vec3(1710.5, 172, 190.5);
    private static BlockPos WALL = new BlockPos(1708, 171, 193), CELL = WALL.north();
    private static BlockPos DECOY_WALL = new BlockPos(1710, 171, 193);
    private static Vec3 CLICK = new Vec3(1708.5, 171.5, 193.0001);
    private static final String TAG = "unlockedcamera_qa_bucket";
    private static volatile boolean running, capturing;
    private static boolean registered, readySent, readyReset, oldToggle, oldShoulder, oldFree, oldKeep, oldKey, oldPause, oldShift;
    private static boolean pickup, deferredAbort;
    private static double oldAmount, oldMax;
    private static CameraType oldType;
    private static Map<Field, Object> cameraFields;
    private static KeyMapping key;
    private static Method position, rotation, dropAim, itemPov;
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static List<BucketItem> items;
    private static final Set<String> complete = new HashSet<>();
    private static final Map<String, Boolean> baselinePositive = new HashMap<>();
    private static final Map<UUID, Entity> owned = new LinkedHashMap<>(), joined = new LinkedHashMap<>();
    private static final Set<UUID> originals = new HashSet<>();
    private static volatile Snapshot snapshot;
    private static volatile boolean serverReady;
    private static volatile Observation firstServer, laterServer, settledServer;
    private static CompletableFuture<Void> task;
    private static EntityType<?> expectedEntity;
    private static int index, mode, phase, age, rows, successes, differences, errors;
    private static String cameraHit, bodyHit, immediateClient, laterClient, useResult, clientPrediction;
    private static String immediatePrediction;
    private static long runtimeLogOffset;
    private record SavedBlock(BlockState state, CompoundTag data) {}
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode, boolean flying,
                            int selected, List<ItemStack> inventory, Map<BlockPos, SavedBlock> blocks) {}
    private record Observation(long tick, String target, String decoy, String held, String inventory, String entities,
                               boolean fluidSuccess, int expectedAtTarget, String dimension) {
        String signature() { return "tick=" + tick + ";target=" + target + ";decoy=" + decoy + ";held=" + held
                + ";inventory=" + inventory + ";entities=" + entities + ";expectedAtTarget=" + expectedAtTarget + ";dimension=" + dimension; }
    }
    private BucketUseProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, false, false, false, false);
    }

    public static void runReview(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, true, false, false, false);
    }

    public static void runPredictionFix(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, true, true, false, false);
    }

    public static void runHandSmoke(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, true, true, true, false);
    }

    public static void runOpenWaterSmoke(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, true, true, true, true);
    }

    private static void start(Minecraft mc, Consumer<String> output, boolean review, boolean predictionFix, boolean hands, boolean water) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isPassenger())
            throw new IllegalStateException("Bucket probe requires idle local singleplayer");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Bucket probe is restricted to disposable QA copy");
        log = output;
        reviewing = review; handSmoke = hands; handIndex = 0; openWater = water;
        WALL = new BlockPos(1708, water ? 173 : 171, 193); CELL = WALL.north();
        DECOY_WALL = new BlockPos(1710, water ? 173 : 171, 193);
        CLICK = water ? new Vec3(1708.5, PLAYER.y + mc.player.getEyeHeight(), 192.5) : new Vec3(1708.5, 171.5, 193.0001);
        ROOT = water ? BASE_ROOT.resolve("prediction-open-water-" + System.currentTimeMillis())
                : hands ? BASE_ROOT.resolve("prediction-hands-" + System.currentTimeMillis())
                : predictionFix ? BASE_ROOT.resolve("prediction-fix-" + System.currentTimeMillis())
                : review ? BASE_ROOT.resolve("review-" + System.currentTimeMillis()) : BASE_ROOT;
        items = BuiltInRegistries.ITEM.stream().filter(BucketItem.class::isInstance).map(BucketItem.class::cast)
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
        if (hands) {
            Set<String> selected = water ? Set.of("minecraft:bucket")
                    : Set.of("minecraft:bucket", "minecraft:water_bucket", "minecraft:cod_bucket", "createpropulsion:coral_bucket");
            items = items.stream().filter(item -> selected.contains(BuiltInRegistries.ITEM.getKey(item).toString())).toList();
            if (items.size() != selected.size()) throw new IllegalStateException("A required hand-smoke bucket is missing");
        }
        Files.createDirectories(ROOT);
        if (review && !predictionFix) {
            Path source = BASE_ROOT.resolve("outcomes.tsv"); Set<String> selected = new TreeSet<>();
            List<String> selection = new ArrayList<>(); selection.add("item\tmode\tprior_status\tprior_prediction");
            Map<String, String[]> latest = new LinkedHashMap<>();
            for (String line : Files.readAllLines(source)) {
                String[] c = line.split("\t", -1);
                if (c.length >= 16 && !c[0].equals("item")) latest.put(c[0] + "\t" + c[1], c);
            }
            for (String[] c : latest.values()) if (!c[0].equals("sophisticatedcore:xp_bucket")
                    && (!c[2].equals("SERVER_EFFECT_CONFIRMED") || c[14].contains("after20TargetMatchesServer=false")
                        || c[14].contains("after20DecoyMatchesServer=false"))) selected.add(c[0]);
            for (String[] c : latest.values()) if (selected.contains(c[0])) selection.add(c[0] + "\t" + c[1] + "\t" + c[2] + "\t" + c[14]);
            Files.write(ROOT.resolve("selection.tsv"), selection);
            Files.writeString(ROOT.resolve("source.txt"), "source=" + source + "\nsha256="
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))
                    + "\nselection=nonpositive_or_after20_client_mismatch_items;fresh_three_views;xp_world_placement_unsupported_excluded\n");
            items = items.stream().filter(item -> selected.contains(BuiltInRegistries.ITEM.getKey(item).toString())).toList();
        }
        identity();
        Files.write(ROOT.resolve("registered-items.txt"), items.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList());
        if (predictionFix) {
            Files.writeString(ROOT.resolve("source.txt"), "purpose=post_fix_client_prediction_and_server_effect;"
                    + (water ? "open_water_no_solid_backdrop;empty_bucket;fresh_three_views_each_main_and_offhand\n"
                            : hands ? "bounded_four_items;fresh_three_views_each_main_and_offhand\n" : "full_live_BucketItem_registry;fresh_three_views\n")
                    + "baseline=qa/bucket-use/outcomes.tsv;baseline_review=qa/bucket-use/review-1793525517563/outcomes.tsv\n"
                    + "oracles=source_verified_caught_entity_wall_top_and_sulfur_cube_release\n"
                    + "upgrade_aquatic:jellyfish_bucket=legal_capture_serialized_stack;does_not_clear_default_stack_NPE\n"
                    + "sophisticatedcore:xp_bucket=known_no_world_block;expected_unsupported_placement_retained\n");
            if (!hands) Files.write(ROOT.resolve("fixture-variants.tsv"), List.of("item\tmode\tfixture\tbaseline_limit",
                    "upgrade_aquatic:jellyfish_bucket\tfirst_person\tBucketable_capture_box_jellyfish\tdefault_stack_missing_entity_data_NPE_not_fixed_or_cleared",
                    "upgrade_aquatic:jellyfish_bucket\tshoulder\tBucketable_capture_box_jellyfish\tdefault_stack_missing_entity_data_NPE_not_fixed_or_cleared",
                    "upgrade_aquatic:jellyfish_bucket\tfreelook\tBucketable_capture_box_jellyfish\tdefault_stack_missing_entity_data_NPE_not_fixed_or_cleared"));
        }
        Files.writeString(ROOT.resolve("scope.txt"), "Every registered instanceof BucketItem, including MobBucketItem and caught-entity subclasses.\n"
                + "SolidBucketItem is a BlockItem and is excluded; milk/poison/remedy drink containers outside BucketItem are excluded.\n"
                + "Real gameMode.useItem, default stacks, " + (hands ? "main and offhand" : "main hand") + ", stationary overworld basin. Mobs frozen only after actual join.\n"
                + "First-person baseline faces the intended target; shoulder/freelook keep body yaw zero, allowing independent body POV prediction.\n"
                + "Server success certifies immediate source pickup/fluid placement or expected mob release at the target, not every fluid context/variant.\n");
        Path file = ROOT.resolve("outcomes.tsv"); complete.clear(); baselinePositive.clear();
        if (Files.exists(file)) for (String line : Files.readAllLines(file)) {
            String[] c = line.split("\t", -1);
            if (c.length < 14 || c[0].equals("item") || c[2].equals("ERROR")) continue;
            complete.add(c[0] + "\t" + c[1]);
            if (c[1].equals("first_person")) baselinePositive.put(c[0] + "\tMAIN_HAND", c[2].equals("SERVER_EFFECT_CONFIRMED"));
        }
        boolean append = Files.exists(file); writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!append) row("item", "mode", "status", "expected_action", "expected_fluid", "expected_entity", "camera_hit", "body_pov_hit",
                "client_use", "client_immediate", "server_immediate", "client_after_20_server_ticks", "server_after_20_ticks", "comparison", "client_prediction", "limits",
                "client_after_80_server_ticks", "server_after_80_ticks");
        cameraFields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch", "shoulderSide"}) {
            Field f = field(UnlockedCameraClient.class, name); cameraFields.put(f, f.get(null));
        }
        key = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null); oldKey = key.isDown();
        oldType = mc.options.getCameraType(); oldPause = mc.options.pauseOnLostFocus; oldShift = mc.options.keyShift.isDown();
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldFree = ClientConfig.ENABLE_FREELOOK.get(); oldKeep = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true);
        rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); rotation.setAccessible(true);
        dropAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); dropAim.setAccessible(true);
        itemPov = Item.class.getDeclaredMethod("getPlayerPOVHitResult", Level.class,
                net.minecraft.world.entity.player.Player.class, ClipContext.Fluid.class); itemPov.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(BucketUseProbe::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, BucketUseProbe::join);
            registered = true;
        }
        index = mode = phase = age = rows = successes = differences = errors = 0;
        snapshot = null; capturing = deferredAbort = false; running = true; mc.setScreen(null); mc.options.pauseOnLostFocus = false;
        task = server(mc, p -> {
            QaPacketBarrier.install(mc.getSingleplayerServer());
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            Map<BlockPos, SavedBlock> blocks = new LinkedHashMap<>();
            for (BlockPos mutable : BlockPos.betweenClosed(water ? 1699 : 1702, 169, water ? 182 : 184, water ? 1720 : 1718, 177, water ? 202 : 200)) {
                BlockPos pos = mutable.immutable(); var be = p.serverLevel().getBlockEntity(pos);
                if (be != null) throw new IllegalStateException("Bucket fixture contains existing block entity " + pos);
                blocks.put(pos, new SavedBlock(p.serverLevel().getBlockState(pos), be == null ? null : be.saveWithFullMetadata(p.registryAccess())));
            }
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getAbilities().flying, p.getInventory().selected, inventory, blocks);
            originals.clear(); owned.clear();
            for (Entity entity : p.serverLevel().getAllEntities()) if (entity != null) originals.add(entity.getUUID());
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = false; p.onUpdateAbilities();
        });
        log.accept("BUCKET_USE START items=" + items.size() + " scheduledViews=" + items.size() * 3 * (hands ? 2 : 1) + " alreadyRecorded=" + complete.size()
                + " modes=first_person,shoulder,freelook hands=" + (hands ? "main_hand,off_hand" : "main_hand")
                + " action=gameMode.useItem;clientPOV_and_serverEffect_separate file=" + file);
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("QA disconnected");
            if (deferredAbort) {
                if (task != null && !task.isDone()) return;
                if (task != null) try { task.join(); } catch (Throwable failure) { log.accept("BUCKET_USE DRAINED_TASK_ERROR " + failure); }
                deferredAbort = false; finish(mc, "ABORT"); return;
            }
            if (++age > 1800) throw new IllegalStateException("Bucket fixture timeout phase=" + phase + " position=" + mc.player.position()
                    + " held=" + mc.player.getItemInHand(hand()) + " serverReady=" + serverReady);
            if (phase == 0) { if (!task.isDone()) return; task.join(); phase = 1; age = 0; }
            while (index < items.size() && complete.contains(id() + "\t" + view())) advance();
            if (index >= items.size()) { finish(mc, "DONE"); return; }
            pose(mc);
            switch (phase) {
                case 1 -> prepare(mc);
                case 2 -> {
                    if (!task.isDone()) return;
                    task.join();
                    if (!readyReset) { dropAim.invoke(null); readyReset = true; age = 0; }
                    boolean clientReady = mc.player.position().distanceToSqr(PLAYER) < .04
                            && mc.player.getItemInHand(hand()).getItem() == items.get(index)
                            && (openWater ? mc.level.getBlockState(WALL).getCollisionShape(mc.level, WALL).isEmpty() : mc.level.getBlockState(WALL).is(Blocks.STONE))
                            && (pickup ? mc.level.getFluidState(CELL).isSource() : mc.level.getBlockState(CELL).isAir());
                    if (age < 4 || !clientReady || mode == 2 && UnlockedCameraClient.continuousAimAngles() == null) return;
                    if (!readySent) {
                        mc.player.connection.send(new ServerboundSetCarriedItemPacket(0));
                        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(bodyYaw(), bodyPitch(mc), mc.player.onGround()));
                        readySent = true;
                        task = QaPacketBarrier.send(mc, p -> {
                            serverReady = teleportReady(p) && p.serverLevel() == snapshot.level && p.position().distanceToSqr(PLAYER) < .04
                                    && p.getItemInHand(hand()).getItem() == items.get(index);
                            if (serverReady) { joined.clear(); capturing = true; }
                        });
                        return;
                    }
                    if (!serverReady) { readySent = false; return; }
                    mc.gameRenderer.pick(1); cameraHit = describe(mc.hitResult);
                    if (!openWater && (!(mc.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(WALL))) {
                        record("NEEDS_CAMERA_FIXTURE", "Camera did not select intended back wall"); next(); return;
                    }
                    if (openWater) {
                        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
                        var forward = mc.gameRenderer.getMainCamera().getLookVector();
                        Vec3 end = camera.add(new Vec3(forward.x(), forward.y(), forward.z()).scale(64));
                        BlockHitResult fluidRay = mc.level.clip(new ClipContext(camera, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
                        BlockHitResult solidRay = mc.level.clip(new ClipContext(camera, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                        if (fluidRay.getType() != HitResult.Type.BLOCK || !fluidRay.getBlockPos().equals(CELL) || solidRay.getType() != HitResult.Type.MISS) {
                            record("NEEDS_CAMERA_FIXTURE", "Open-water camera control failed fluid=" + describe(fluidRay) + ";solid=" + describe(solidRay)); next(); return;
                        }
                        cameraHit += ";source_ray=" + describe(fluidRay) + ";solid_backdrop=" + describe(solidRay);
                    }
                    bodyHit = describe((HitResult) itemPov.invoke(null, mc.level, mc.player,
                            pickup ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE));
                    runtimeLogOffset = Files.size(Path.of("logs", "latest.log"));
                    useResult = mc.gameMode.useItem(mc.player, hand()).toString();
                    immediateClient = clientObservation(mc);
                    immediatePrediction = expectedEntity != null && items.get(index).content == Fluids.EMPTY ? "NOT_APPLICABLE_ENTITY_SERVER_ONLY"
                            : String.valueOf(pickup ? mc.level.getFluidState(CELL).isEmpty()
                            : mc.level.getFluidState(CELL).getType().isSame(items.get(index).content));
                    task = QaPacketBarrier.send(mc, p -> { firstServer = observe(p); capturing = false; });
                    phase = 3; age = 0;
                }
                case 3 -> {
                    if (!task.isDone()) return;
                    task.join();
                    if (age < 20) return;
                    task = QaPacketBarrier.send(mc, p -> laterServer = observe(p)); phase = 4; age = 0;
                }
                case 4 -> {
                    if (!task.isDone()) return;
                    task.join();
                    if (laterServer.tick - firstServer.tick < 20) {
                        if (age % 4 == 0) task = QaPacketBarrier.send(mc, p -> laterServer = observe(p));
                        return;
                    }
                    phase = 5; age = 0;
                }
                case 5 -> {
                    // The ordered future completes on the server thread before
                    // already-sent block updates necessarily reach the client.
                    if (age < 3) return;
                    laterClient = clientObservation(mc);
                    clientPrediction = "immediateTargetEffect=" + immediatePrediction + ";after20TargetMatchesServer="
                            + block(mc.level, CELL).equals(laterServer.target) + ";after20DecoyMatchesServer="
                            + block(mc.level, decoyCell()).equals(laterServer.decoy);
                    if (reviewing) { phase = 6; age = 0; }
                    else completeCase();
                }
                case 6 -> {
                    if (mc.getSingleplayerServer().overworld().getGameTime() - firstServer.tick < 80) return;
                    task = QaPacketBarrier.send(mc, p -> settledServer = observe(p)); phase = 7; age = 0;
                }
                case 7 -> {
                    if (!task.isDone()) return;
                    task.join();
                    if (age < 3) return;
                    settledClient = clientObservation(mc) + ";clientGameTime=" + mc.level.getGameTime();
                    clientPrediction += ";after80TargetMatchesServer=" + block(mc.level, CELL).equals(settledServer.target)
                            + ";after80DecoyMatchesServer=" + block(mc.level, decoyCell()).equals(settledServer.decoy);
                    completeCase();
                }
                default -> throw new IllegalStateException("Unexpected phase=" + phase);
            }
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            log.accept("BUCKET_USE ERROR item=" + id() + " mode=" + view() + " phase=" + phase + " " + error);
            try {
                if (mc.player != null && mc.getSingleplayerServer() != null && task != null && !task.isDone()) {
                    if (snapshot != null && phase != 0) record("ERROR", error + ";abort_after_mutation_drain");
                    deferredAbort = true; capturing = false;
                    log.accept("BUCKET_USE WAIT_MUTATION_DRAIN phase=" + phase); return;
                }
                if (snapshot == null || phase == 0 || mc.player == null || mc.player.level().dimension() != snapshot.level.dimension()) finish(mc, "ABORT");
                else { record("ERROR", error.toString()); next(); }
            } catch (Throwable restore) { running = false; log.accept("BUCKET_USE ABORT restore=" + restore); }
        }
    }

    private static void prepare(Minecraft mc) throws Exception {
        BucketItem item = items.get(index); expectedEntity = expectedEntity(item);
        expectedMobCell = reviewing && item.content == Fluids.EMPTY && expectedEntity != null
                && (item.getClass().getName().startsWith("com.crispytwig.naturalist.")
                    || item.getClass().getName().endsWith("ModernRemantBucket")) ? WALL.above() : CELL;
        pickup = item.content == Fluids.EMPTY && expectedEntity == null;
        firstServer = laterServer = settledServer = null; cameraHit = bodyHit = immediateClient = laterClient = settledClient = ""; useResult = "NOT_SENT";
        fixtureKind = reviewing && id().equals("upgrade_aquatic:jellyfish_bucket")
                ? "captured_stack_serialized_by_Bucketable;fixture_entity=upgrade_aquatic:box_jellyfish" : "default_stack";
        clientPrediction = immediatePrediction = "NOT_MEASURED";
        readySent = readyReset = serverReady = false; dropAim.invoke(null); mc.gameMode.releaseUsingItem(mc.player);
        task = server(mc, p -> {
            capturing = false; cleanup();
            if (p.serverLevel() != snapshot.level) throw new IllegalStateException("Bucket changed player dimension");
            for (BlockPos pos : snapshot.blocks.keySet()) {
                boolean highFloor = openWater && pos.getY() <= 172 && !(Math.abs(pos.getX() - 1710) <= 1 && Math.abs(pos.getZ() - 190) <= 1);
                BlockState state = pos.getY() <= 170 || highFloor ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                if (snapshot.level.getBlockState(pos) != state) snapshot.level.setBlock(pos, state, 3);
            }
            // Leave the basin's front open: a full-height front wall occludes
            // the lower shoulder ray. The wide saved stone floor bounds the
            // short fluid-flow observation without obstructing either view.
            for (BlockPos wall : openWater ? List.of(BlockPos.containing(PLAYER).below())
                    : List.of(WALL, CELL.west(), CELL.east(), DECOY_WALL.east(), DECOY_WALL.south(),
                    DECOY_WALL.west(), DECOY_WALL.north().east(), BlockPos.containing(PLAYER).below()))
                snapshot.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
            snapshot.level.setBlock(DECOY_WALL, pickup ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState(), 3);
            snapshot.level.setBlock(CELL, pickup ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            p.stopRiding(); p.stopUsingItem(); p.closeContainer(); p.getInventory().clearContent(); p.getInventory().selected = 0;
            ItemStack stack = item.getDefaultInstance();
            if (reviewing && id().equals("upgrade_aquatic:jellyfish_bucket")) {
                Entity fixture = expectedEntity.create(p.serverLevel());
                if (!(fixture instanceof Bucketable bucketable)) throw new IllegalStateException("Jellyfish fixture is not Bucketable");
                stack = bucketable.getBucketItemStack(); bucketable.saveToBucketTag(stack);
                if (!stack.is(item)) throw new IllegalStateException("Capture serializer returned a different bucket item");
                log.accept("BUCKET_USE CAPTURE_FIXTURE item=" + id() + " entity=" + BuiltInRegistries.ENTITY_TYPE.getKey(expectedEntity)
                        + " serializer=Bucketable.getBucketItemStack+saveToBucketTag;fixture_entity_not_added_to_world=true");
            }
            p.setItemInHand(hand(), stack); p.getCooldowns().removeCooldown(item);
            p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z); p.setDeltaMovement(Vec3.ZERO);
            p.setYRot(bodyYaw()); p.setXRot(fixedPitch()); p.setYHeadRot(p.getYRot()); p.containerMenu.broadcastFullState();
        });
        phase = 2; age = 0;
    }

    private static EntityType<?> expectedEntity(BucketItem item) throws Exception {
        if (reviewing && id().equals("minecraft:sulfur_cube_bucket"))
            return BuiltInRegistries.ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.parse("minecraft:sulfur_cube"));
        if (reviewing && id().equals("upgrade_aquatic:jellyfish_bucket"))
            return BuiltInRegistries.ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.parse("upgrade_aquatic:box_jellyfish"));
        if (!(item instanceof MobBucketItem)) return null;
        try { return (EntityType<?>) item.getClass().getMethod("type").invoke(item); }
        catch (NoSuchMethodException absent) {
            Field type = MobBucketItem.class.getDeclaredField("type"); type.setAccessible(true);
            return (EntityType<?>) type.get(item);
        }
    }
    private static float bodyYaw() { return mode == 0 ? (float) Math.toDegrees(Math.atan2(PLAYER.x - CLICK.x, CLICK.z - PLAYER.z)) : 0; }
    private static float fixedPitch() { return pitch(PLAYER.add(0, 1.62, 0), CLICK); }
    private static float bodyPitch(Minecraft mc) { return pitch(mc.player.getEyePosition(), CLICK); }
    private static void pose(Minecraft mc) throws Exception {
        ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        field(UnlockedCameraClient.class, "active").setBoolean(null, mode == 1);
        field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4); field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
        Vec3 eye = mc.player.getEyePosition(); float cameraEyeYaw = (float) Math.toDegrees(Math.atan2(eye.x - CLICK.x, CLICK.z - eye.z));
        float bodyPitch = pitch(eye, CLICK), yaw = mode == 0 ? cameraEyeYaw : 0;
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setXRot(bodyPitch); mc.player.xRotO = bodyPitch;
        mc.player.getInventory().selected = 0; mc.player.setSprinting(false); mc.player.setShiftKeyDown(false);
        mc.options.keyShift.setDown(false); mc.player.input.shiftKeyDown = false; mc.setScreen(null);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, mode == 2 ? cameraEyeYaw : 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, 0);
        key.setDown(mode == 2); mc.options.setCameraType(mode == 1 ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        Vec3 camera = mode == 1 ? eye.add(.95, 0, -4) : eye; Vec3 ray = CLICK.subtract(camera);
        position.invoke(mc.gameRenderer.getMainCamera(), camera);
        rotation.invoke(mc.gameRenderer.getMainCamera(), (float) Math.toDegrees(Math.atan2(-ray.x, ray.z)), pitch(camera, CLICK), 0f);
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object custom = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (custom != null) {
            Class<?> type = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
            Object turn = type.getConstructor(float.class, float.class).newInstance(yaw, bodyPitch);
            custom.getClass().getMethod("setCameraRotation", type).invoke(custom, turn);
            Field playerRotation = custom.getClass().getDeclaredField("playerRotation"); playerRotation.setAccessible(true); playerRotation.set(custom, turn);
        }
    }
    private static float pitch(Vec3 origin, Vec3 target) {
        Vec3 delta = target.subtract(origin); return (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
    }
    private static BlockPos decoyCell() { return pickup ? DECOY_WALL : DECOY_WALL.north(); }
    private static String block(Level level, BlockPos pos) {
        var fluid = level.getFluidState(pos);
        return level.getBlockState(pos) + "/fluid=" + BuiltInRegistries.FLUID.getKey(fluid.getType()) + "/source=" + fluid.isSource();
    }
    private static String clientObservation(Minecraft mc) {
        return "target=" + block(mc.level, CELL) + ";decoy=" + block(mc.level, decoyCell())
                + ";held=" + BuiltInRegistries.ITEM.getKey(mc.player.getItemInHand(hand()).getItem());
    }
    private static Observation observe(ServerPlayer p) {
        Map<String, String> entities = new TreeMap<>(); int atTarget = 0;
        for (Entity entity : joined.values()) {
            if (entity.isRemoved()) continue;
            entities.put(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + ":" + entity.getId(), entity.position().toString());
            if (entity.getType() == expectedEntity && Math.abs(entity.getX() - (expectedMobCell.getX() + .5)) < .75
                    && Math.abs(entity.getZ() - (expectedMobCell.getZ() + .5)) < .75 && Math.abs(entity.getY() - expectedMobCell.getY()) < 2) atTarget++;
        }
        boolean fluid = pickup ? (openWater ? !p.serverLevel().getFluidState(CELL).isSource() : p.serverLevel().getFluidState(CELL).isEmpty())
                : items.get(index).content != Fluids.EMPTY && p.serverLevel().getFluidState(CELL).getType().isSame(items.get(index).content);
        Map<String, Integer> inventory = new TreeMap<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (!stack.isEmpty()) inventory.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return new Observation(p.serverLevel().getGameTime(), block(p.serverLevel(), CELL), block(p.serverLevel(), decoyCell()),
                BuiltInRegistries.ITEM.getKey(p.getItemInHand(hand()).getItem()).toString(), inventory.toString(), entities.toString(), fluid, atTarget,
                p.serverLevel().dimension().location().toString());
    }
    private static void join(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!running || !capturing || snapshot == null || event.getLevel() != snapshot.level || event.loadedFromDisk() || event.isCanceled()
                || originals.contains(entity.getUUID()) || entity.position().distanceToSqr(CELL.getCenter()) > 256) return;
        joined.put(entity.getUUID(), entity); owned.put(entity.getUUID(), entity); entity.addTag(TAG);
        entity.setNoGravity(true); entity.setInvulnerable(true); entity.setDeltaMovement(Vec3.ZERO);
        if (entity instanceof Mob mob) mob.setNoAi(true);
    }
    private static void cleanup() {
        for (Entity entity : new ArrayList<>(owned.values())) if (!entity.isRemoved()) entity.discard();
        owned.clear(); joined.clear();
    }
    private static boolean teleportReady(ServerPlayer p) {
        try { Field field = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient"); field.setAccessible(true); return field.get(p.connection) == null; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot read teleport readiness", error); }
    }
    private static void record(String status, String detail) throws Exception {
        boolean positive = status.equals("SERVER_EFFECT_CONFIRMED");
        String comparison = mode == 0 ? "BASELINE" : !baselinePositive.getOrDefault(id() + "\t" + hand(), false) ? "BASELINE_NOT_POSITIVE"
                : positive ? "MATCHED_SERVER_EFFECT" : "BASELINE_EFFECT_NOT_REPRODUCED";
        if (mode == 0) baselinePositive.put(id() + "\t" + hand(), positive);
        if (positive) successes++; else if (status.equals("ERROR")) errors++; else differences++;
        row(id(), view(), status, pickup ? "SOURCE_PICKUP" : expectedEntity != null ? "MOB_RELEASE" : "FLUID_PLACEMENT",
                BuiltInRegistries.FLUID.getKey(items.get(index).content), expectedEntity == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(expectedEntity),
                cameraHit, bodyHit, useResult, immediateClient, firstServer == null ? "" : firstServer.signature(), laterClient,
                laterServer == null ? "" : laterServer.signature(), comparison, clientPrediction,
                fixtureKind + ";" + hand().name().toLowerCase(Locale.ROOT) + ";" + (openWater ? "open_water_horizontal_source_ray_no_solid_backdrop" : "stationary_overworld_basin")
                        + ";client_prediction_separate;mob_AI_gravity_disabled_after_join;variants_and_special_contexts_not_certified;expected_mob_cell=" + expectedMobCell + ";" + detail,
                settledClient, settledServer == null ? "" : settledServer.signature());
        writer.flush(); rows++;
        if (!status.equals("ERROR")) complete.add(id() + "\t" + view());
        if (!positive) log.accept("BUCKET_USE RESULT item=" + id() + " mode=" + view() + " status=" + status + " comparison=" + comparison + " detail=" + detail);
    }
    private static void completeCase() throws Exception {
        String packetError = packetErrors();
        boolean effect = expectedEntity == null ? firstServer.fluidSuccess : firstServer.expectedAtTarget > 0
                && (items.get(index).content == Fluids.EMPTY || firstServer.fluidSuccess);
        record(!packetError.isEmpty() ? "ERROR" : effect ? "SERVER_EFFECT_CONFIRMED" : "EXPECTED_SERVER_EFFECT_NOT_OBSERVED", packetError);
        next();
    }
    private static void next() { capturing = false; advance(); phase = 1; age = 0; if (mode == 0 && (index % 8 == 0 || index == items.size()))
        log.accept("BUCKET_USE PROGRESS items=" + index + "/" + items.size() + " rows=" + rows + " confirmed=" + successes + " differences=" + differences + " errors=" + errors); }
    private static void advance() { if (++mode == 3) { mode = 0; if (handSmoke && ++handIndex < 2) return; handIndex = 0; index++; } }
    private static InteractionHand hand() { return handIndex == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND; }
    private static String view() { return MODES[mode] + (handSmoke ? "_" + hand().name().toLowerCase(Locale.ROOT) : ""); }
    private static String id() { return index < items.size() ? BuiltInRegistries.ITEM.getKey(items.get(index)).toString() : "complete"; }
    private static String describe(HitResult hit) { return hit.getType() + ":" + (hit instanceof BlockHitResult block ? block.getBlockPos() + ":" + block.getDirection() : hit.getLocation()); }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var id = mc.player.getUUID(); return mc.getSingleplayerServer().submit(() -> action.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id)));
    }
    private static String packetErrors() throws Exception {
        String window;
        try (var file = new java.io.RandomAccessFile(Path.of("logs", "latest.log").toFile(), "r")) {
            long start = Math.min(runtimeLogOffset, file.length()); int length = Math.toIntExact(Math.min(file.length() - start, 2_000_000));
            byte[] bytes = new byte[length]; file.seek(start); file.readFully(bytes); window = new String(bytes, StandardCharsets.UTF_8);
        }
        String[] lines = window.split("\\R"); List<String> errors = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (lines[i].contains("Failed to handle packet") && lines[i].contains("ServerboundUseItemPacket"))
            errors.add(i + 1 < lines.length ? lines[i + 1].trim() : lines[i]);
        if (errors.isEmpty()) return "";
        Files.writeString(ROOT.resolve("runtime-packet-errors.log"), "\nitem=" + id() + " mode=" + view() + "\n" + window,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "runtime_packet_exceptions=" + errors;
    }
    private static void finish(Minecraft mc, String status) throws Exception {
        running = false; capturing = false; writer.close(); mc.gameMode.releaseUsingItem(mc.player);
        for (var entry : cameraFields.entrySet()) entry.getKey().set(null, entry.getValue());
        key.setDown(oldKey); mc.options.setCameraType(oldType); mc.options.pauseOnLostFocus = oldPause;
        mc.options.keyShift.setDown(oldShift); mc.player.input.shiftKeyDown = oldShift; mc.player.setShiftKeyDown(oldShift);
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        ClientConfig.ENABLE_FREELOOK.set(oldFree); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeep);
        if (snapshot != null) server(mc, p -> {
            cleanup(); p.stopUsingItem(); p.stopRiding(); p.closeContainer();
            for (var entry : snapshot.blocks.entrySet()) {
                snapshot.level.setBlock(entry.getKey(), entry.getValue().state, 3); var be = snapshot.level.getBlockEntity(entry.getKey());
                if (be != null && entry.getValue().data != null) be.loadWithComponents(entry.getValue().data, p.registryAccess());
            }
            p.getInventory().clearContent(); for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
            p.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
            p.containerMenu.broadcastFullState(); log.accept("BUCKET_USE RESTORED");
        });
        log.accept("BUCKET_USE " + status + " items=" + items.size() + " rows=" + rows + " confirmed=" + successes + " differences=" + differences + " errors=" + errors);
    }
    private static void identity() throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (var mod : net.neoforged.fml.ModList.get().getMods().stream().sorted(Comparator.comparing(m -> m.getModId())).toList())
            hash.update((mod.getModId() + "=" + mod.getVersion() + "\n").getBytes(StandardCharsets.UTF_8));
        for (Class<?> type : List.of(BucketUseProbe.class, UnlockedCameraClient.class))
            try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) { hash.update(stream.readAllBytes()); }
        String value = HexFormat.of().formatHex(hash.digest()); Path file = ROOT.resolve("identity.txt");
        if (Files.exists(file) && !Files.readString(file).trim().equals(value)) throw new IllegalStateException("Bucket resume identity changed; preserve old evidence first");
        Files.writeString(file, value + System.lineSeparator());
    }
    private static void row(Object... cells) throws Exception {
        for (int i = 0; i < cells.length; i++) { if (i > 0) writer.write('\t'); writer.write(String.valueOf(cells[i]).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')); }
        writer.newLine();
    }
}
