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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

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

/** Real default egg use-on-block requests. Spawned mobs are frozen only after their spawn attempt. */
public final class SpawnEggUseProbe {
    private static final Path BASE_ROOT = Path.of("qa", "spawn-egg-use");
    private static Path ROOT = BASE_ROOT;
    private static boolean reviewing;
    private static boolean reviewRemaining;
    private static volatile long preparedAt;
    private static int lastBlockerLog;
    private static final String ACK = "unlockedcamera_qa_egg_ack", TAG = "unlockedcamera_qa_spawn_egg";
    private static final String[] MODES = {"first_person", "shoulder", "freelook"};
    private static final Vec3 PLAYER = new Vec3(1710.5, 170, 190.5);
    private static final BlockPos TARGET = new BlockPos(1708, 169, 192);
    private static final Vec3 CLICK = Vec3.atBottomCenterOf(TARGET.above());
    private static volatile boolean running, capturing;
    private static boolean registered, readyReset, oldToggle, oldShoulder, oldFree, oldKeep, oldKey, oldPause, deferredAbort, drainAck;
    private static double oldAmount, oldMax;
    private static CameraType oldType;
    private static Map<Field, Object> fields;
    private static KeyMapping key;
    private static Method position, rotation, dropAim;
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static List<Item> eggs;
    private static final Set<String> complete = new HashSet<>();
    private static final Map<String, Boolean> positives = new HashMap<>();
    private static int index, mode, phase, age, token, rows, successful, limited, errors;
    private static volatile Snapshot snapshot;
    private static volatile Ready ready;
    private static volatile Observation ack;
    private static CompletableFuture<Void> task;
    private static final Map<UUID, Entity> owned = new LinkedHashMap<>();
    private static final Set<UUID> originals = new HashSet<>(), finalized = new HashSet<>();
    private static final Map<UUID, Entity> joined = new LinkedHashMap<>();
    private static int canceledJoins;
    private static String expected, picked, use;
    private static Set<String> expectedTypes = Set.of();
    private static long runtimeLogOffset;

    private record SavedBlock(BlockState state, CompoundTag data) {}
    private record Snapshot(ServerLevel level, Vec3 pos, float yaw, float pitch, GameType gameType,
                            List<ItemStack> inventory, int selected, boolean flying, float health,
                            List<MobEffectInstance> effects, Map<BlockPos, SavedBlock> blocks) {}
    private record Ready(boolean teleportReady, Item held) {}
    private record Observation(int token, String entities, int expectedJoined, int expectedLive,
                               int finalizedEggs, int canceledJoins, String held, String dimension) {}

    private SpawnEggUseProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, false);
    }

    public static void runReview(Minecraft mc, Consumer<String> output) throws Exception {
        reviewRemaining = false;
        start(mc, output, true);
    }

    public static void runReviewRemaining(Minecraft mc, Consumer<String> output) throws Exception {
        reviewRemaining = true;
        start(mc, output, true);
    }

    private static void start(Minecraft mc, Consumer<String> output, boolean review) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isPassenger())
            throw new IllegalStateException("Spawn egg probe requires an idle integrated-server player");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Spawn egg probe is restricted to the disposable QA copy");
        log = output;
        reviewing = review;
        ROOT = review ? BASE_ROOT.resolve("review-" + System.currentTimeMillis()) : BASE_ROOT;
        eggs = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof SpawnEggItem
                        || item.getClass().getName().equals("com.telepathicgrunt.the_bumblezone.items.SentryWatcherSpawnEgg"))
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
        Files.createDirectories(ROOT);
        if (review) {
            Path source = BASE_ROOT.resolve("outcomes.tsv");
            if (reviewRemaining) try (var directories = Files.list(BASE_ROOT)) {
                source = directories.filter(Files::isDirectory).filter(path -> path.getFileName().toString().startsWith("review-"))
                        .filter(path -> !path.equals(ROOT) && Files.exists(path.resolve("outcomes.tsv")))
                        .max(Comparator.comparing(path -> path.getFileName().toString())).orElseThrow().resolve("outcomes.tsv");
            }
            Map<String, String[]> latest = new LinkedHashMap<>();
            for (String line : Files.readAllLines(source)) {
                String[] cells = line.split("\t", -1);
                if (cells.length >= 13 && !cells[0].equals("item")) latest.put(cells[0] + "\t" + cells[1], cells);
            }
            Set<String> failed = new TreeSet<>();
            for (String[] cells : latest.values()) if (!cells[2].equals("SPAWNED_EXPECTED_ENTITY")) failed.add(cells[0]);
            eggs = eggs.stream().filter(item -> failed.contains(BuiltInRegistries.ITEM.getKey(item).toString())).toList();
            Files.writeString(ROOT.resolve("source.txt"), "source=" + source + "\nsha256="
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))
                    + "\nselection=each_item_with_any_latest_non_SPAWNED_EXPECTED_ENTITY;fresh_all_three_views\n");
            List<String> selection = new ArrayList<>(); selection.add("item\tmode\tprior_status");
            for (String[] cells : latest.values()) if (failed.contains(cells[0])) selection.add(cells[0] + "\t" + cells[1] + "\t" + cells[2]);
            Files.write(ROOT.resolve("selection.tsv"), selection);
        }
        identity(); complete.clear(); positives.clear();
        Files.write(ROOT.resolve("registered-items.txt"), eggs.stream()
                .map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList());
        Path evidence = ROOT.resolve("outcomes.tsv");
        if (Files.exists(evidence)) for (String line : Files.readAllLines(evidence)) {
            String[] cells = line.split("\t", -1);
            if (cells.length < 13 || cells[0].equals("item") || cells[2].equals("ERROR")) continue;
            complete.add(cells[0] + "\t" + cells[1]);
            if (cells[1].equals("first_person")) positives.put(cells[0], cells[2].equals("SPAWNED_EXPECTED_ENTITY"));
        }
        boolean append = Files.exists(evidence);
        writer = Files.newBufferedWriter(evidence, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!append) row("item", "mode", "status", "expected_entity", "picked_block", "use_result", "server_entities",
                "expected_joined", "expected_live", "spawn_egg_finalize_count", "canceled_joins", "comparison", "limits");
        fields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch", "shoulderSide"}) {
            Field f = field(name); fields.put(f, f.get(null));
        }
        key = (KeyMapping) field("FREELOOK_KEY").get(null); oldKey = key.isDown();
        oldType = mc.options.getCameraType(); oldPause = mc.options.pauseOnLostFocus;
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldFree = ClientConfig.ENABLE_FREELOOK.get(); oldKeep = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true);
        rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); rotation.setAccessible(true);
        dropAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); dropAim.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(SpawnEggUseProbe::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, SpawnEggUseProbe::joined);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, SpawnEggUseProbe::finalized);
            registered = true;
        }
        index = mode = phase = age = rows = successful = limited = errors = 0;
        snapshot = null; capturing = deferredAbort = drainAck = false; running = true;
        mc.options.pauseOnLostFocus = false; mc.setScreen(null);
        task = server(mc, p -> {
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            Map<BlockPos, SavedBlock> blocks = new LinkedHashMap<>();
            for (BlockPos mutable : BlockPos.betweenClosed(1704, 169, 186, 1714, 177, 196)) {
                BlockPos pos = mutable.immutable(); var be = p.serverLevel().getBlockEntity(pos);
                if (be != null) throw new IllegalStateException("Egg fixture contains existing block entity " + pos);
                blocks.put(pos, new SavedBlock(p.serverLevel().getBlockState(pos), be == null ? null : be.saveWithFullMetadata(p.registryAccess())));
            }
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    inventory, p.getInventory().selected, p.getAbilities().flying, p.getHealth(),
                    p.getActiveEffects().stream().map(MobEffectInstance::new).toList(), blocks);
            originals.clear(); owned.clear();
            for (Entity entity : snapshot.level.getAllEntities()) if (entity != null) originals.add(entity.getUUID());
            mc.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(ACK)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer source = context.getSource().getPlayerOrException();
                        if (running && source.getUUID().equals(p.getUUID())) {
                            capturing = false;
                            ack = observe(source, IntegerArgumentType.getInteger(context, "token"));
                        }
                        return 1;
                    })));
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = false; p.onUpdateAbilities(); reset(p);
        });
        log.accept("SPAWN_EGG_USE START eggs=" + eggs.size() + " scheduledViews=" + eggs.size() * 3
                + " alreadyRecorded=" + complete.size() + " modes=first_person,shoulder,freelook actualAction=gameMode.useItemOn"
                + " freezeAfterSpawn=true file=" + evidence);
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("QA world disconnected");
            if (deferredAbort) {
                if (task != null && !task.isDone() || drainAck && (ack == null || ack.token != token)) return;
                if (task != null) try { task.join(); } catch (Throwable failure) { log.accept("SPAWN_EGG_USE DRAINED_TASK_ERROR " + failure); }
                deferredAbort = false; finish(mc, "ABORT"); return;
            }
            if (++age > 1800) throw new IllegalStateException("Fixture timeout phase=" + phase + " ready=" + ready
                    + " clientHeld=" + BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem())
                    + " clientPosition=" + mc.player.position());
            if (phase == 0) {
                if (!task.isDone()) return;
                task.join(); phase = 1; age = 0;
            }
            while (index < eggs.size() && complete.contains(id() + "\t" + MODES[mode])) advance();
            if (index >= eggs.size()) { finish(mc, "DONE"); return; }
            pose(mc);
            switch (phase) {
                case 1 -> prepare(mc);
                case 2 -> {
                    if (!task.isDone()) return;
                    task.join();
                    if (!readyReset) { dropAim.invoke(null); readyReset = true; age = 0; }
                    if (age < 4 || mc.player.position().distanceToSqr(PLAYER) > .04
                            || mc.player.getMainHandItem().getItem() != eggs.get(index)
                            || mc.level.getBlockState(TARGET).getBlock() != Blocks.STONE) return;
                    if (ready == null || !ready.teleportReady || ready.held != eggs.get(index)) {
                        if (age % 3 == 0) task = server(mc, p -> ready = new Ready(teleportReady(p), p.getMainHandItem().getItem()));
                        return;
                    }
                    if (mode == 2 && UnlockedCameraClient.continuousAimAngles() == null) return;
                    mc.gameRenderer.pick(1);
                    picked = describePick(mc);
                    if (!(mc.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(TARGET) || hit.getDirection() != Direction.UP) {
                        if (reviewing && mc.getSingleplayerServer().overworld().getGameTime() - preparedAt < 200) {
                            if (age - lastBlockerLog >= 20) {
                                lastBlockerLog = age;
                                log.accept("SPAWN_EGG_USE WAIT_TARGET_CLEAR item=" + id() + " mode=" + MODES[mode]
                                        + " elapsedServerTicks=" + (mc.getSingleplayerServer().overworld().getGameTime() - preparedAt)
                                        + " blocker=" + picked + ";no_use_request_sent");
                                // Naturalist keeps non-entity multipart objects
                                // in a client index. Clean only the observed
                                // already removed shark's own parts through
                                // the mod's public lifecycle API, then re-pick
                                // on a later tick before sending any request.
                                EggMultipartFixtureProbe.run(mc, log);
                                WhaleMultipartFixtureProbe.run(mc, log);
                            }
                            return;
                        }
                        record("NEEDS_TARGET_FIXTURE", "", null, "crosshair_did_not_select_expected_top_face"); next(); return;
                    }
                    picked = hit.getBlockPos() + ":" + hit.getDirection() + ":" + hit.getLocation();
                    // Arm capture on the server before sending the real use request.
                    task = server(mc, p -> { joined.clear(); finalized.clear(); canceledJoins = 0; capturing = true; });
                    phase = 3; age = 0;
                }
                case 3 -> {
                    if (!task.isDone()) return;
                    task.join(); pose(mc); mc.gameRenderer.pick(1);
                    if (!(mc.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(TARGET) || hit.getDirection() != Direction.UP)
                        throw new IllegalStateException("Target changed after capture was armed: " + mc.hitResult);
                    runtimeLogOffset = Files.size(Path.of("logs", "latest.log"));
                    use = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).toString();
                    ++token; ack = null; mc.player.connection.sendCommand(ACK + " " + token);
                    phase = 4; age = 0;
                }
                case 4 -> {
                    if (ack == null || ack.token != token) return;
                    String packetErrors = packetErrors();
                    String status = !packetErrors.isEmpty() ? "ERROR" : ack.expectedLive > 0 ? "SPAWNED_EXPECTED_ENTITY"
                            : ack.expectedJoined > 0 ? "EXPECTED_ENTITY_REMOVED_IMMEDIATELY"
                            : ack.entities.equals("{}") ? "NO_ENTITY_OBSERVED" : "UNEXPECTED_ENTITY_TYPE";
                    record(status, use, ack, packetErrors.isEmpty() ? "" : packetErrors); next();
                }
                default -> throw new IllegalStateException("Unexpected phase=" + phase);
            }
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            log.accept("SPAWN_EGG_USE ERROR item=" + id() + " mode=" + MODES[mode] + " phase=" + phase + " " + error);
            try {
                if (mc.player != null && mc.getSingleplayerServer() != null
                        && (task != null && !task.isDone() || phase == 4 && (ack == null || ack.token != token))) {
                    if (snapshot != null && phase != 0) record("ERROR", use, null, error + ";abort_after_mutation_drain");
                    deferredAbort = true; drainAck = phase == 4; capturing = false;
                    log.accept("SPAWN_EGG_USE WAIT_MUTATION_DRAIN phase=" + phase + " ack=" + drainAck); return;
                }
                if (snapshot == null || phase == 0) finish(mc, "ABORT");
                else { record("ERROR", use, null, error.toString()); next(); }
            } catch (Throwable restore) { running = false; log.accept("SPAWN_EGG_USE ABORT restore=" + restore); }
        }
    }

    private static void prepare(Minecraft mc) throws Exception {
        mc.gameMode.releaseUsingItem(mc.player); dropAim.invoke(null); mc.setScreen(null);
        ready = null; readyReset = false; ack = null; use = "NOT_SENT"; picked = ""; expected = "";
        lastBlockerLog = 0;
        Item egg = eggs.get(index);
        EntityType<?> type = egg instanceof SpawnEggItem standard ? standard.getType(egg.getDefaultInstance())
                : (EntityType<?>) egg.getClass().getMethod("getType", ItemStack.class).invoke(egg, egg.getDefaultInstance());
        expected = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(type));
        expectedTypes = Set.of(expected);
        if (reviewing && id().equals("upgrade_aquatic:jellyfish_spawn_egg")
                && egg.getDefaultInstance().getOrDefault(net.minecraft.core.component.DataComponents.ENTITY_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).isEmpty()) {
            // This exact installed item independently randomizes every getType
            // call. The oracle must use its COMMON registry family, not a
            // different random draw made while preparing the fixture.
            List<?> entries = (List<?>) Class.forName("com.teamabnormals.upgrade_aquatic.core.other.JellyfishRegistry")
                    .getMethod("collectJelliesMatchingRarity", net.minecraft.world.item.Rarity.class)
                    .invoke(null, net.minecraft.world.item.Rarity.COMMON);
            Set<String> family = new TreeSet<>();
            for (Object entry : entries) {
                java.util.function.Supplier<?> supplier = (java.util.function.Supplier<?>) entry.getClass().getMethod("jellyfish").invoke(entry);
                family.add(BuiltInRegistries.ENTITY_TYPE.getKey((EntityType<?>) supplier.get()).toString());
            }
            if (family.isEmpty()) throw new IllegalStateException("Default jellyfish egg COMMON family is empty");
            expectedTypes = Set.copyOf(family); expected = "COMMON_RANDOM_FAMILY:" + String.join(",", family);
            log.accept("SPAWN_EGG_USE EXPECTED_FAMILY item=" + id() + " allowed=" + family
                    + " source=JellyfishRegistry.collectJelliesMatchingRarity(COMMON);default_ENTITY_DATA_empty=true");
        }
        task = server(mc, p -> {
            capturing = false; cleanup(); reset(p);
            p.stopRiding(); p.stopUsingItem(); p.closeContainer(); p.getInventory().clearContent(); p.getInventory().selected = 0;
            p.setItemInHand(InteractionHand.MAIN_HAND, egg.getDefaultInstance()); p.getCooldowns().removeCooldown(egg);
            p.setYRot(mode == 2 ? 0 : 45); p.setXRot(mode == 2 ? 0 : pitch(PLAYER.add(0, p.getEyeHeight(), 0), CLICK));
            p.setYHeadRot(p.getYRot()); p.containerMenu.broadcastFullState();
            preparedAt = p.serverLevel().getGameTime();
        });
        phase = 2; age = 0;
    }

    private static void reset(ServerPlayer p) {
        if (p.serverLevel() != snapshot.level) throw new IllegalStateException("Player dimension changed during egg fixture");
        for (BlockPos pos : snapshot.blocks.keySet()) {
            BlockState state = pos.getY() == 169 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (snapshot.level.getBlockState(pos) != state) snapshot.level.setBlock(pos, state, 3);
        }
        p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z); p.setDeltaMovement(Vec3.ZERO);
    }

    private static void pose(Minecraft mc) throws Exception {
        ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        field("active").setBoolean(null, mode == 1); field("targetDistance").setFloat(null, 4);
        field("shoulderSide").setInt(null, 1);
        Vec3 eye = mc.player.getEyePosition(); float bodyPitch = pitch(eye, CLICK);
        field("freelookYaw").setFloat(null, mode == 2 ? 45 : 0); field("freelookPitch").setFloat(null, mode == 2 ? bodyPitch : 0);
        key.setDown(mode == 2); mc.options.setCameraType(mode == 1 ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        float bodyYaw = mode == 2 ? 0 : 45; float actualPitch = mode == 2 ? 0 : bodyPitch;
        mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw; mc.player.setXRot(actualPitch); mc.player.xRotO = actualPitch;
        mc.player.getInventory().selected = 0; mc.player.setSprinting(false); mc.setScreen(null);
        Vec3 forward = Vec3.directionFromRotation(0, 45);
        Vec3 camera = mode == 1 ? eye.subtract(forward.scale(4)).add(Math.sqrt(.5) * .95, 0, Math.sqrt(.5) * .95) : eye;
        Vec3 ray = CLICK.subtract(camera);
        position.invoke(mc.gameRenderer.getMainCamera(), camera);
        rotation.invoke(mc.gameRenderer.getMainCamera(), (float) Math.toDegrees(Math.atan2(-ray.x, ray.z)), pitch(camera, CLICK), 0f);
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object custom = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (custom != null) {
            Class<?> type = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
            Object turn = type.getConstructor(float.class, float.class).newInstance(bodyYaw, actualPitch);
            custom.getClass().getMethod("setCameraRotation", type).invoke(custom, turn);
            Field rotationField = custom.getClass().getDeclaredField("playerRotation"); rotationField.setAccessible(true); rotationField.set(custom, turn);
        }
    }

    private static float pitch(Vec3 origin, Vec3 target) {
        Vec3 delta = target.subtract(origin);
        return (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
    }

    private static String describePick(Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult hit) {
            Entity entity = hit.getEntity();
            return "ENTITY:" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + ":uuid=" + entity.getUUID()
                    + ":removed=" + entity.isRemoved() + ":tickCount=" + entity.tickCount + ":position=" + entity.position();
        }
        return String.valueOf(mc.hitResult);
    }

    private static void finalized(FinalizeSpawnEvent event) {
        if (!running || !capturing || snapshot == null || event.getEntity().level() != snapshot.level
                || event.getSpawnType() != MobSpawnType.SPAWN_EGG || event.getEntity().position().distanceToSqr(CLICK) > 256) return;
        finalized.add(event.getEntity().getUUID());
    }

    private static void joined(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!running || !capturing || snapshot == null || event.getLevel() != snapshot.level || event.loadedFromDisk()
                || originals.contains(entity.getUUID()) || entity.position().distanceToSqr(CLICK) > 256) return;
        if (event.isCanceled()) { canceledJoins++; return; }
        joined.put(entity.getUUID(), entity); owned.put(entity.getUUID(), entity); entity.addTag(TAG);
        entity.setNoGravity(true); entity.setInvulnerable(true);
        if (entity instanceof Mob mob) mob.setNoAi(true);
    }

    private static Observation observe(ServerPlayer p, int request) {
        Map<String, Integer> types = new TreeMap<>(); int expectedJoined = 0, live = 0;
        for (Entity entity : joined.values()) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(); types.merge(type, 1, Integer::sum);
            if (expectedTypes.contains(type)) {
                expectedJoined++;
                if (!entity.isRemoved() && p.serverLevel().getEntity(entity.getUUID()) != null) live++;
            }
        }
        return new Observation(request, types.toString(), expectedJoined, live, finalized.size(), canceledJoins,
                BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString(), p.serverLevel().dimension().location().toString());
    }

    private static boolean teleportReady(ServerPlayer p) throws Exception {
        Field pending = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
        pending.setAccessible(true); return pending.get(p.connection) == null;
    }

    private static void record(String status, String result, Observation observation, String detail) throws Exception {
        boolean positive = status.equals("SPAWNED_EXPECTED_ENTITY");
        String comparison = mode == 0 ? "BASELINE" : !positives.getOrDefault(id(), false) ? "BASELINE_NOT_POSITIVE"
                : positive ? "MATCHED_EXPECTED_SPAWN" : "EXPECTED_SPAWN_NOT_REPRODUCED";
        if (mode == 0) positives.put(id(), positive);
        if (positive) successful++; else if (status.equals("ERROR")) errors++; else limited++;
        row(id(), MODES[mode], status, expected, picked, result, observation == null ? "" : observation.entities,
                observation == null ? "" : observation.expectedJoined, observation == null ? "" : observation.expectedLive,
                observation == null ? "" : observation.finalizedEggs, observation == null ? "" : observation.canceledJoins,
                comparison, "default_stack;main_hand;stone_top_face;immediate_spawn_only;AI_and_gravity_disabled_after_join;variants_and_long_term_behavior_not_certified;" + detail);
        writer.flush(); rows++;
        if (!status.equals("ERROR")) complete.add(id() + "\t" + MODES[mode]);
        if (!positive) log.accept("SPAWN_EGG_USE RESULT item=" + id() + " mode=" + MODES[mode] + " status=" + status + " expected=" + expected + " detail=" + detail);
    }

    private static void next() {
        capturing = false; advance(); phase = 1; age = 0;
        if (mode == 0 && (index % 16 == 0 || index == eggs.size()))
            log.accept("SPAWN_EGG_USE PROGRESS eggs=" + index + "/" + eggs.size() + " rows=" + rows + " expectedSpawns=" + successful + " limited=" + limited + " errors=" + errors);
    }
    private static void advance() { if (++mode == 3) { mode = 0; index++; } }
    private static String id() { return index < eggs.size() ? BuiltInRegistries.ITEM.getKey(eggs.get(index)).toString() : "complete"; }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static CompletableFuture<Void> server(Minecraft mc, CheckedAction action) {
        UUID id = mc.player.getUUID();
        return mc.getSingleplayerServer().submit(() -> {
            try { action.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id)); }
            catch (Exception error) { throw new IllegalStateException("Spawn egg fixture server task", error); }
        });
    }
    @FunctionalInterface private interface CheckedAction { void accept(ServerPlayer player) throws Exception; }
    private static void cleanup() {
        // Snapshot the references before discard: multipart removal can mutate level iterables.
        for (Entity entity : new ArrayList<>(owned.values())) if (!entity.isRemoved()) entity.discard();
        owned.clear(); joined.clear(); finalized.clear();
    }

    private static String packetErrors() throws Exception {
        String window;
        try (var file = new java.io.RandomAccessFile(Path.of("logs", "latest.log").toFile(), "r")) {
            long start = Math.min(runtimeLogOffset, file.length()); int size = Math.toIntExact(Math.min(file.length() - start, 2_000_000));
            byte[] bytes = new byte[size]; file.seek(start); file.readFully(bytes); window = new String(bytes, StandardCharsets.UTF_8);
        }
        String[] lines = window.split("\\R"); List<String> errors = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (lines[i].contains("Failed to handle packet") && lines[i].contains("ServerboundUseItemOnPacket"))
            errors.add(i + 1 < lines.length ? lines[i + 1].trim() : lines[i]);
        if (errors.isEmpty()) return "";
        Files.writeString(ROOT.resolve("runtime-packet-errors.log"), "\nitem=" + id() + " mode=" + MODES[mode] + "\n" + window,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "runtime_packet_exceptions=" + errors;
    }

    private static void finish(Minecraft mc, String status) throws Exception {
        running = false; capturing = false; writer.close(); mc.gameMode.releaseUsingItem(mc.player);
        for (var entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        key.setDown(oldKey); mc.options.setCameraType(oldType); mc.options.pauseOnLostFocus = oldPause;
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        ClientConfig.ENABLE_FREELOOK.set(oldFree); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeep);
        if (snapshot != null) server(mc, p -> {
            cleanup(); p.stopUsingItem(); p.stopRiding(); p.closeContainer();
            for (var entry : snapshot.blocks.entrySet()) {
                snapshot.level.setBlock(entry.getKey(), entry.getValue().state, 3);
                var be = snapshot.level.getBlockEntity(entry.getKey());
                if (be != null && entry.getValue().data != null) be.loadWithComponents(entry.getValue().data, p.registryAccess());
            }
            p.getInventory().clearContent();
            for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.gameType);
            p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
            p.removeAllEffects(); for (var effect : snapshot.effects) p.addEffect(new MobEffectInstance(effect)); p.setHealth(snapshot.health);
            p.teleportTo(snapshot.level, snapshot.pos.x, snapshot.pos.y, snapshot.pos.z, snapshot.yaw, snapshot.pitch);
            p.containerMenu.broadcastFullState(); log.accept("SPAWN_EGG_USE RESTORED");
        });
        log.accept("SPAWN_EGG_USE " + status + " eggs=" + eggs.size() + " rows=" + rows + " expectedSpawns=" + successful + " limited=" + limited + " errors=" + errors);
    }

    private static void identity() throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (var mod : net.neoforged.fml.ModList.get().getMods().stream().sorted(Comparator.comparing(m -> m.getModId())).toList())
            hash.update((mod.getModId() + "=" + mod.getVersion() + "\n").getBytes(StandardCharsets.UTF_8));
        for (Class<?> type : List.of(SpawnEggUseProbe.class, UnlockedCameraClient.class))
            try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) { hash.update(stream.readAllBytes()); }
        String value = HexFormat.of().formatHex(hash.digest()); Path file = ROOT.resolve("identity.txt");
        if (Files.exists(file) && !Files.readString(file).trim().equals(value))
            throw new IllegalStateException("Egg resume identity changed; preserve old evidence before another run");
        Files.writeString(file, value + System.lineSeparator());
    }
    private static void row(Object... values) throws Exception {
        for (int i = 0; i < values.length; i++) {
            if (i != 0) writer.write('\t');
            writer.write(String.valueOf(values[i]).replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
        }
        writer.newLine();
    }
}
