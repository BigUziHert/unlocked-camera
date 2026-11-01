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
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;

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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real air-use smoke/parity for every non-block default item, not every contextual ability. */
public final class ItemUseProbe {
    private static Vec3 playerPosition;
    private static AABB area;
    private static int floorY;
    private static final Path ROOT = Path.of("qa", "item-air-use");
    private static Path evidenceRoot;
    private static final String ACK = "unlockedcamera_qa_item_observe";
    private static boolean registered, running;
    private static boolean reviewing;
    private static boolean freelookProfile, oldFreelook;
    private static KeyMapping freelookKey;
    private static volatile boolean waitingForAbilities;
    private static volatile List<String> activeAbilities = List.of();
    private static volatile Item preparedServerHeld;
    private static boolean serverReadinessChecked;
    private static long runtimeLogOffset;
    private static List<Item> items;
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static int index, mode, phase, age, holdTicks, token, completed, differences, errors, clientTick, useStartedTick;
    private static boolean released;
    private static String useResult, screen, initialUse, initialScreen;
    private static Observation initialObservation, baseline;
    private static volatile Observation acknowledgement;
    private static volatile Snapshot snapshot;
    private static CompletableFuture<Void> task;
    private static Set<UUID> originals;
    private static Map<Field, Object> fields;
    private static CameraType cameraType;
    private static boolean toggle, shoulder;
    private static double amount, max;
    private static boolean pausedOnFocus;
    private static Method cameraPosition, cameraRotation, dropHeldAim;
    private static boolean sawAbilityWait, reviewRemaining;
    private static boolean deferredAbort, drainAcknowledgement, actionInFlight;
    private static String focusedFreelookItem;
    private static boolean fixtureMotionRecoverySent;
    private static Method getCurios, saveCurios, loadCurios, equippedCurios;
    private static volatile ListTag originalCurios;

    private record SavedBlock(BlockState state, CompoundTag data) {}
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode, int selected,
                            List<ItemStack> inventory, float health, int food, float saturation,
                            List<MobEffectInstance> effects, Map<BlockPos, SavedBlock> blocks) {}
    private record Observation(int token, String held, boolean using, String menu, String effects, String curios, String equipment,
                               String entities, String dimension, String error) {
        String signature() { return held + "|" + using + "|" + menu + "|" + effects + "|curios=" + curios + "|equipment=" + equipment + "|" + entities + "|" + dimension + "|" + error; }
    }

    private ItemUseProbe() {}

    /** Generic `probe ItemUseProbe`; explicit registration also permits first loading after startup. */
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, false);
    }

    /** Rechecks only original differences/errors with stricter equipment synchronization. */
    public static void runReview(Minecraft mc, Consumer<String> output) throws Exception {
        reviewRemaining = false;
        start(mc, output, true);
    }

    public static void runReviewRemaining(Minecraft mc, Consumer<String> output) throws Exception {
        reviewRemaining = true;
        start(mc, output, true);
    }

    /** Fresh paired first-person/free-look air use for the complete non-block registry. */
    public static void runFreelook(Minecraft mc, Consumer<String> output) throws Exception {
        start(mc, output, false, true);
    }

    /** Fresh paired review of only the completed free-look sweep's latest errors/differences. */
    public static void runFreelookReview(Minecraft mc, Consumer<String> output) throws Exception {
        reviewRemaining = false;
        start(mc, output, true, true, true);
    }

    public static void runStormsEdgeRecheck(Minecraft mc, Consumer<String> output) throws Exception {
        focusedFreelookItem = "simplyswords:storms_edge";
        reviewRemaining = false;
        start(mc, output, true, true, true);
    }

    private static void start(Minecraft mc, Consumer<String> output, boolean review) throws Exception {
        start(mc, output, review, false);
    }

    private static void start(Minecraft mc, Consumer<String> output, boolean review, boolean free) throws Exception {
        start(mc, output, review, free, false);
    }

    private static void start(Minecraft mc, Consumer<String> output, boolean review, boolean free, boolean freeReview) throws Exception {
        if (running) throw new IllegalStateException("Item air-use probe already running");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || mc.player.isPassenger())
            throw new IllegalStateException("Item air-use requires idle local singleplayer");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Item air-use restricted to build/modpack-test/game");
        log = output;
        reviewing = review || free;
        freelookProfile = free;
        // The full free-look sweep shares the already loaded horizontal arena
        // with the serial integration jobs, at its own restored floor height.
        playerPosition = free ? new Vec3(1710.5, 160, 190.5) : new Vec3(1774.5, 150, 230.5);
        floorY = (int) playerPosition.y - 1;
        area = new AABB(playerPosition.x - 128.5, playerPosition.y - 50, playerPosition.z - 128.5,
                playerPosition.x + 127.5, playerPosition.y + 150, playerPosition.z + 127.5);
        evidenceRoot = freeReview ? ROOT.resolve("freelook-review-" + System.currentTimeMillis())
                : free ? ROOT.resolve("freelook") : review ? ROOT.resolve("review-" + System.currentTimeMillis()) : ROOT;
        Files.createDirectories(evidenceRoot);
        ensureIdentity();
        Set<String> complete = new HashSet<>();
        Set<String> selected = new HashSet<>();
        if (review) {
            Path candidates = freeReview ? ROOT.resolve("freelook").resolve("outcomes.tsv") : ROOT.resolve("outcomes.tsv");
            if (reviewRemaining) {
                try (var directories = Files.list(ROOT)) {
                    candidates = directories.filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().startsWith("review-"))
                            .filter(path -> !path.equals(evidenceRoot) && Files.exists(path.resolve("outcomes.tsv")))
                            .max(Comparator.comparing(path -> path.getFileName().toString())).orElseThrow().resolve("outcomes.tsv");
                }
                log.accept("ITEM_AIR_USE REVIEW_REMAINING source=" + candidates);
            }
            if (freeReview) {
                Map<String, String> latest = new LinkedHashMap<>();
                for (String line : Files.readAllLines(candidates)) {
                    String[] cells = line.split("\t", -1);
                    if (cells.length >= 2 && !cells[0].equals("item")) latest.put(cells[0], cells[1]);
                }
                latest.forEach((item, status) -> {
                    if (status.equals("ERROR") || status.equals("OBSERVED_DIFFERENCE_REVIEW")) selected.add(item);
                });
                if (focusedFreelookItem != null) selected.retainAll(Set.of(focusedFreelookItem));
                Files.write(evidenceRoot.resolve("selected-items.txt"), selected.stream().sorted().toList());
                List<String> selection = new ArrayList<>();
                selection.add("item\tprior_status");
                selected.stream().sorted().forEach(item -> selection.add(item + "\t" + latest.get(item)));
                Files.write(evidenceRoot.resolve("selection.tsv"), selection);
                String sourceIdentity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(candidates)));
                Files.writeString(evidenceRoot.resolve("source.txt"), "source=" + candidates + "\nsha256=" + sourceIdentity
                        + "\nselection=latest_ERROR_or_OBSERVED_DIFFERENCE_REVIEW_only\n");
                log.accept("ITEM_AIR_USE FREELOOK_REVIEW_SELECTION source=" + candidates + " sourceSha256=" + sourceIdentity
                        + " latestItems=" + latest.size() + " selected=" + selected.size() + " originalRowsPreserved=true");
            } else {
                for (String line : Files.readAllLines(candidates)) {
                    String[] cells = line.split("\t", -1);
                    if (cells.length >= 2 && (cells[1].equals("OBSERVED_DIFFERENCE_REVIEW") || cells[1].equals("ERROR")))
                        selected.add(cells[0]);
                }
            }
            // These looked like paired no-ops, but the installed mod throws a
            // server packet exception before its locator result is observable.
            if (!reviewRemaining && !freeReview) BuiltInRegistries.ITEM.stream()
                    .filter(item -> item.getClass().getName().equals("io.github.razordevs.deep_aether.item.misc.DungeonCompass"))
                    .forEach(item -> selected.add(BuiltInRegistries.ITEM.getKey(item).toString()));
        }
        Path file = evidenceRoot.resolve("outcomes.tsv");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                String[] cells = line.split("\t", -1);
                if (cells.length >= 8 && !cells[0].equals("item") && !cells[1].equals("ERROR")) complete.add(cells[0]);
            }
        }
        items = BuiltInRegistries.ITEM.stream().filter(item -> !(item instanceof BlockItem))
                .filter(item -> !review || selected.contains(BuiltInRegistries.ITEM.getKey(item).toString()))
                .filter(item -> !complete.contains(BuiltInRegistries.ITEM.getKey(item).toString()))
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
        boolean append = Files.exists(file);
        writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!append) row("item", "status", "first_use", "first_screen", "first_server", secondaryMode() + "_use", secondaryMode() + "_screen", secondaryMode() + "_server", "limits");
        fields = new LinkedHashMap<>();
        for (String name : new String[] {"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field f = field(name); fields.put(f, f.get(null));
        }
        cameraType = mc.options.getCameraType();
        freelookKey = (KeyMapping) field("FREELOOK_KEY").get(null);
        oldFreelook = freelookKey.isDown();
        toggle = ClientConfig.TOGGLE_MOD.get(); shoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        amount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); max = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        pausedOnFocus = mc.options.pauseOnLostFocus;
        cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        cameraPosition.setAccessible(true); cameraRotation.setAccessible(true);
        dropHeldAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim");
        dropHeldAim.setAccessible(true);
        try {
            getCurios = Class.forName("top.theillusivec4.curios.api.CuriosApi")
                    .getMethod("getCuriosInventory", LivingEntity.class);
            Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            saveCurios = handler.getMethod("saveInventory", boolean.class);
            loadCurios = handler.getMethod("loadInventory", ListTag.class);
            equippedCurios = handler.getMethod("getEquippedCurios");
        } catch (ClassNotFoundException absent) { getCurios = null; }
        SimplySwordsQaIsolation.initialize(mc, evidenceRoot);
        if (!registered) { NeoForge.EVENT_BUS.addListener(ItemUseProbe::tick); registered = true; }
        index = mode = phase = age = completed = differences = errors = 0;
        deferredAbort = drainAcknowledgement = actionInFlight = waitingForAbilities = sawAbilityWait = false;
        snapshot = null; acknowledgement = null; running = true;
        mc.setScreen(null); mc.options.pauseOnLostFocus = false;
        log.accept("ITEM_AIR_USE START pending=" + items.size() + " alreadyRecorded=" + complete.size()
                + " views=first_person," + secondaryMode() + " hand=MAIN_HAND maxUseTicks=40 review=" + review
                + " profile=" + (free ? "freelook" : "offset") + " arena=" + playerPosition + " file=" + file);
        task = server(mc, p -> {
            mc.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(ACK)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer source = context.getSource().getPlayerOrException();
                        if (running && source.getUUID().equals(p.getUUID()))
                            acknowledgement = observe(source, IntegerArgumentType.getInteger(context, "token"));
                        return 1;
                    })));
            List<ItemStack> inventory = new ArrayList<>();
            for (int n = 0; n < p.getInventory().getContainerSize(); n++) inventory.add(p.getInventory().getItem(n).copy());
            Map<BlockPos, SavedBlock> blocks = new LinkedHashMap<>();
            // Review contextual effects away from the original platform edge.
            // Void Core's sixth 1.25m rune sat exactly on that edge at yaw zero.
            int centerX = (int) Math.floor(playerPosition.x), centerZ = (int) Math.floor(playerPosition.z);
            int minX = centerX - (review || free ? 20 : 5), maxX = centerX + (review || free ? 20 : 5);
            int minZ = centerZ - (review || free ? 20 : 5), maxZ = centerZ + (review || free ? 20 : 7);
            for (BlockPos mutable : BlockPos.betweenClosed(minX, floorY, minZ, maxX, floorY + 6, maxZ)) {
                BlockPos pos = mutable.immutable();
                var be = p.serverLevel().getBlockEntity(pos);
                if (free && be != null) throw new IllegalStateException("Free-look air-use fixture contains existing block entity at " + pos);
                blocks.put(pos, new SavedBlock(p.serverLevel().getBlockState(pos),
                        be == null ? null : be.saveWithFullMetadata(p.registryAccess())));
            }
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(),
                    p.gameMode.getGameModeForPlayer(), p.getInventory().selected, inventory, p.getHealth(),
                    p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(),
                    p.getActiveEffects().stream().map(MobEffectInstance::new).toList(), blocks);
            originalCurios = clearCurios(p);
            originals = new HashSet<>();
            for (Entity e : p.serverLevel().getEntities(p, area)) originals.add(e.getUUID());
            p.setGameMode(GameType.CREATIVE);
            resetArena(p);
        });
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) {
            log.accept("ITEM_AIR_USE ABORT disconnected; disposable fixture remains"); running = false; close(); return;
        }
        try {
            clientTick++;
            if (deferredAbort) { drainBeforeAbort(mc); return; }
            if (sawAbilityWait && !waitingForAbilities) { age = 0; sawAbilityWait = false; }
            if (waitingForAbilities) sawAbilityWait = true;
            age++;
            if (age > (waitingForAbilities ? 2400 : reviewing ? 1200 : 400)) {
                timeoutDiagnostics(mc);
                throw new IllegalStateException((waitingForAbilities ? "Ability isolation timeout" : "Timeout") + " phase=" + phase);
            }
            if (phase == 0) {
                if (!task.isDone()) return;
                task.join();
                if (mc.player.position().distanceToSqr(playerPosition) > .02 || age < 25) return;
                phase = 1; age = 0;
            }
            if (index >= items.size()) { finish(mc, "DONE"); return; }
            if (mc.screen != null && mc.screen.isPauseScreen()) mc.setScreen(null);
            if (freelookProfile && phase >= 2) pose(mc, false);
            // Bound use even if the initial ordered acknowledgement is delayed.
            if ((phase == 3 || phase == 4) && !released && clientTick - useStartedTick >= holdTicks) {
                mc.gameMode.releaseUsingItem(mc.player);
                released = true;
            }
            switch (phase) {
                case 1 -> prepare(mc);
                case 2 -> {
                    if (!task.isDone()) return;
                    task.join();
                    Item current = items.get(index);
                    if (waitingForAbilities) {
                        if (age % 7 == 0) task = server(mc, p -> {
                            activeAbilities = SimplySwordsQaIsolation.active(p);
                            if (!activeAbilities.isEmpty()) return;
                            resetArena(p); p.removeAllEffects();
                            p.getFoodData().setFoodLevel(5); p.getFoodData().setSaturation(0);
                            p.setItemInHand(InteractionHand.MAIN_HAND, current.getDefaultInstance());
                            SimplySwordsQaIsolation.clearCooldown(p, p.getMainHandItem());
                            p.getCooldowns().removeCooldown(current); p.containerMenu.broadcastFullState();
                            waitingForAbilities = false;
                        });
                        return;
                    }
                    ItemStack expected = expectedDefault(current);
                    if (focusedFreelookItem != null) {
                        // Only pre-request fixture setup, after the public
                        // ability gate has drained. Never constrain a real dash.
                        mc.player.setDeltaMovement(Vec3.ZERO);
                        if (mc.player.position().distanceToSqr(playerPosition) > .03 && !fixtureMotionRecoverySent) {
                            fixtureMotionRecoverySent = true;
                            task = server(mc, p -> {
                                if (!SimplySwordsQaIsolation.active(p).isEmpty())
                                    throw new IllegalStateException("Ability restarted during fixture-only motion reset");
                                log.accept("ITEM_AIR_USE FIXTURE_MOTION_RESET item=" + id() + " mode=" + mode
                                        + " from=" + p.position() + " velocity=" + p.getDeltaMovement()
                                        + " afterPublicAbilityDrain=true;before_use_request=true");
                                resetArena(p); p.hurtMarked = true;
                            });
                            return;
                        }
                    }
                    boolean autoConsumed = autoConsumedBeforeUse(current);
                    if (autoConsumed ? !mc.player.getMainHandItem().isEmpty() : expected.isEmpty() ? !mc.player.getMainHandItem().isEmpty()
                            : mc.player.getMainHandItem().getItem() != expected.getItem()) return;
                    if (mc.player.position().distanceToSqr(playerPosition) > .03) return;
                    // Identical held stacks across views can be present before the
                    // server's clear packet arrives; equipped armor must be empty.
                    if (age < 4 || !emptyExceptMainHand(mc.player)) return;
                    // The server future can complete before its health packet is drained.
                    // In particular, the preceding food may have filled the hunger bar.
                    if (mc.player.getFoodData().getFoodLevel() != 5) return;
                    if (!curiosSummary(mc.player).equals("{}")) return;
                    Item expectedServerItem = autoConsumed ? ItemStack.EMPTY.getItem() : expected.getItem();
                    if (!serverReadinessChecked || preparedServerHeld != expectedServerItem) {
                        if (!serverReadinessChecked || age % 7 == 0) {
                            // Server fixture initialization resets body yaw. Refresh the
                            // real next pre-tick hold once after that task completed;
                            // retain its cache when the actual use request is sent.
                            if (!serverReadinessChecked) dropHeldAim.invoke(null);
                            serverReadinessChecked = true;
                            task = server(mc, p -> {
                                preparedServerHeld = p.getMainHandItem().getItem();
                                if (preparedServerHeld == expectedServerItem) {
                                    // Legacy aliases migrate after equip. Cooldowns are
                                    // keyed by the actual item, not its old registry alias.
                                    SimplySwordsQaIsolation.clearCooldown(p, p.getMainHandItem());
                                    p.getCooldowns().removeCooldown(preparedServerHeld);
                                }
                            });
                        }
                        return;
                    }
                    mc.player.getInventory().selected = 0;
                    mc.player.getCooldowns().removeCooldown(current);
                    mc.player.getCooldowns().removeCooldown(mc.player.getMainHandItem().getItem());
                    mc.player.setYRot(0); mc.player.setXRot(0);
                    pose(mc, false);
                    if (reviewing) {
                        runtimeLogOffset = Files.size(Path.of("logs", "latest.log"));
                        log.accept("ITEM_AIR_USE REQUEST item=" + id() + " mode=" + (mode == 0 ? "first_person" : secondaryMode())
                                + " runtimeLogStart=" + runtimeLogOffset + " clientBodyYaw=" + mc.player.getYRot()
                                + " cameraYaw=" + mc.gameRenderer.getMainCamera().getYRot()
                                + " heldAim=" + java.util.Arrays.toString(UnlockedCameraClient.continuousAimAngles()));
                    }
                    actionInFlight = !autoConsumed;
                    released = false;
                    useResult = autoConsumed ? "NOT_SENT_AUTO_CONSUMED_BEFORE_USE"
                            : mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).toString();
                    screen = mc.screen == null ? "none" : mc.screen.getClass().getName();
                    holdTicks = mc.player.isUsingItem() ? Math.min(40, Math.max(1, mc.player.getUseItem().getUseDuration(mc.player))) : 0;
                    useStartedTick = clientTick;
                    released = holdTicks == 0;
                    initialObservation = null;
                    acknowledge(mc); phase = holdTicks == 0 ? 5 : 3; age = 0;
                }
                case 3 -> {
                    if (acknowledgement == null || acknowledgement.token != token) return;
                    initialObservation = acknowledgement;
                    if (released) { acknowledge(mc); phase = 5; }
                    else phase = 4;
                    age = 0;
                }
                case 4 -> {
                    if (!released) return;
                    acknowledge(mc); phase = 5; age = 0;
                }
                case 5 -> {
                    if (acknowledgement == null || acknowledgement.token != token) return;
                    actionInFlight = false;
                    Observation result = initialObservation == null ? acknowledgement : combine(initialObservation, acknowledgement);
                    if (reviewing) result = withRuntimePacketErrors(result);
                    if (mode == 0) {
                        baseline = result; initialUse = useResult; initialScreen = screen;
                        mode = 1; prepare(mc);
                    } else {
                        boolean autoConsumed = autoConsumedBeforeUse(items.get(index));
                        boolean autoConsumptionConfirmed = !autoConsumed || baseline.held.equals(stackSignature(ItemStack.EMPTY))
                                && result.held.equals(stackSignature(ItemStack.EMPTY));
                        boolean runtimeFailure = !baseline.error.isEmpty() || !result.error.isEmpty() || !autoConsumptionConfirmed;
                        boolean same = baseline.signature().equals(result.signature())
                                && initialUse.equals(useResult) && initialScreen.equals(screen);
                        if (runtimeFailure) errors++;
                        else if (!same) differences++;
                        boolean noEffect = (initialUse.equals("PASS") || initialUse.equals("FAIL"))
                                && !baseline.using && baseline.effects.equals("[]") && baseline.entities.equals("{}")
                                && baseline.curios.equals("{}") && baseline.equipment.equals("{}")
                                && initialScreen.equals("none") && baseline.menu.endsWith("InventoryMenu")
                                && baseline.held.equals(stackSignature(items.get(index).getDefaultInstance()));
                        String status = runtimeFailure ? "ERROR" : same ? autoConsumed ? "MATCHED_AUTO_CONSUMED_BEFORE_USE"
                                : noEffect ? "MATCHED_NO_OBSERVABLE_EFFECT" : "MATCHED_AIR_USE_SMOKE"
                                : "OBSERVED_DIFFERENCE_REVIEW";
                        row(id(), status, initialUse, initialScreen, baseline.signature(), useResult, screen,
                                result.signature(), autoConsumed ? "default_stack;paired_inventory_tick_consumption;use_not_sent;empty_contents_only;contents_delivery_not_certified"
                                : "default_stack;main_hand;air_use_only;max_40_ticks;no_special_ammo_charge_or_target;effects_not_certified");
                        completed++; index++; mode = 0; phase = 1; age = 0;
                        if (completed % 32 == 0 || index == items.size()) {
                            writer.flush(); log.accept("ITEM_AIR_USE PROGRESS completed=" + completed + "/" + items.size()
                                    + " differences=" + differences + " errors=" + errors + " last=" + idPrevious());
                        }
                        if (index < items.size()) prepare(mc);
                    }
                }
                default -> throw new IllegalStateException("Unknown item phase " + phase);
            }
        } catch (Throwable failure) {
            while (failure.getCause() != null) failure = failure.getCause();
            log.accept("ITEM_AIR_USE ERROR item=" + id() + " mode=" + mode + " " + failure);
            try {
                row(id(), "ERROR", mode, phase, failure, "", "", "", "retry_after_fixture_review");
                writer.flush(); errors++;
                if ((task != null && !task.isDone()) || actionInFlight || (phase >= 3 && phase <= 5)) {
                    deferAbort(mc);
                } else if (phase == 0 || snapshot == null || failure.getMessage() != null
                        && (failure.getMessage().contains("dimension") || failure.getMessage().contains("Ability isolation"))) {
                    finish(mc, "ABORT");
                } else { index++; mode = 0; phase = 1; age = 0; }
            } catch (Throwable nested) { log.accept("ITEM_AIR_USE RESTORE_ERROR " + nested); running = false; close(); }
        }
    }

    private static void deferAbort(Minecraft mc) {
        deferredAbort = true;
        // A release and a fresh command share the actual client packet stream.
        // Observing this token proves that both the use and release were handled
        // before server restoration may be queued, even if the earlier ACK stalled.
        if (actionInFlight || (phase >= 3 && phase <= 5)) {
            if (!released) { mc.gameMode.releaseUsingItem(mc.player); released = true; }
            acknowledge(mc);
            drainAcknowledgement = true;
        }
        log.accept("ITEM_AIR_USE WAIT_MUTATION_DRAIN item=" + id() + " mode=" + mode
                + " phase=" + phase + " taskPending=" + (task != null && !task.isDone())
                + " orderedReleaseAck=" + drainAcknowledgement);
    }

    private static void drainBeforeAbort(Minecraft mc) throws Exception {
        // Do not cancel server.submit: managedBlock can run queued restore tasks
        // reentrantly while the original chunk-loading mutation is still active.
        if (task != null && !task.isDone()) return;
        if (drainAcknowledgement && (acknowledgement == null || acknowledgement.token != token)) return;
        if (task != null && task.isCompletedExceptionally()) {
            try { task.join(); }
            catch (Throwable failure) { log.accept("ITEM_AIR_USE DRAINED_SERVER_ERROR " + failure); }
        }
        log.accept("ITEM_AIR_USE MUTATIONS_DRAINED item=" + id() + " mode=" + mode);
        finish(mc, "ABORT");
    }

    private static void prepare(Minecraft mc) throws Exception {
        mc.setScreen(null);
        mc.player.getInventory().selected = 0;
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        // A few registered packages open during inventoryTick, before use can
        // be requested. Apply the view before equipping those fixtures too.
        pose(mc);
        Item current = items.get(index);
        sawAbilityWait = false;
        fixtureMotionRecoverySent = false;
        serverReadinessChecked = false;
        preparedServerHeld = null;
        task = server(mc, p -> {
            if (p.serverLevel() != snapshot.level) throw new IllegalStateException("Item changed player dimension; needs specialized fixture");
            resetArena(p);
            p.stopUsingItem(); p.closeContainer(); p.stopRiding();
            SimplySwordsQaIsolation.cleanup(p, log);
            clearCurios(p);
            p.getInventory().clearContent(); p.getInventory().selected = 0;
            // Delayed effects outlive their entities. Keep the actor unarmed
            // until public lifecycle/readiness APIs confirm a clean fixture.
            activeAbilities = SimplySwordsQaIsolation.active(p);
            waitingForAbilities = !activeAbilities.isEmpty();
            if (!waitingForAbilities) {
                p.setItemInHand(InteractionHand.MAIN_HAND, current.getDefaultInstance());
                SimplySwordsQaIsolation.clearCooldown(p, p.getMainHandItem());
            } else log.accept("ITEM_AIR_USE WAIT_ACTIVE_ABILITIES item=" + BuiltInRegistries.ITEM.getKey(current)
                    + " mode=" + (mode == 0 ? "first_person" : secondaryMode()) + " gates=" + activeAbilities);
            p.getCooldowns().removeCooldown(current);
            p.getFoodData().setFoodLevel(5); p.getFoodData().setSaturation(0);
            p.removeAllEffects(); p.setHealth(p.getMaxHealth()); p.clearFire();
            float bodyYaw = freelookProfile && mode == 0 ? 60 : 0;
            p.setYRot(bodyYaw); p.setXRot(0); p.setYHeadRot(bodyYaw);
            // A reset can restore the same server stack while the client still
            // holds a predicted armor swap, which differential sync would omit.
            p.containerMenu.broadcastFullState();
        });
        phase = 2; age = 0;
    }

    private static void pose(Minecraft mc) throws Exception {
        pose(mc, true);
    }

    private static void pose(Minecraft mc, boolean resetHeldAim) throws Exception {
        // A pose change here happens after the camera's Pre tick; otherwise the
        // first-person baseline could consume the previous shoulder aim cache.
        if (resetHeldAim) dropHeldAim.invoke(null);
        ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        boolean free = freelookProfile && mode != 0;
        field("active").setBoolean(null, !freelookProfile && mode != 0); field("targetDistance").setFloat(null, 4);
        field("freelookYaw").setFloat(null, free ? 60 : 0); field("freelookPitch").setFloat(null, 0);
        freelookKey.setDown(free);
        mc.options.setCameraType(freelookProfile || mode == 0 ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        if (freelookProfile) {
            float bodyYaw = mode == 0 ? 60 : 0;
            mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw; mc.player.setXRot(0); mc.player.xRotO = 0;
            Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
            Object custom = manager.getClass().getMethod("getCustomCamera").invoke(manager);
            if (custom != null) {
                Class<?> type = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
                Object turn = type.getConstructor(float.class, float.class).newInstance(bodyYaw, 0f);
                custom.getClass().getMethod("setCameraRotation", type).invoke(custom, turn);
                Field playerRotation = custom.getClass().getDeclaredField("playerRotation");
                playerRotation.setAccessible(true); playerRotation.set(custom, turn);
            }
        }
        cameraPosition.invoke(mc.gameRenderer.getMainCamera(), freelookProfile || mode == 0 ? mc.player.getEyePosition()
                : mc.player.getEyePosition().add(.95, 0, -4));
        cameraRotation.invoke(mc.gameRenderer.getMainCamera(), freelookProfile ? 60f : 0f, 0f, 0f);
    }

    private static void resetArena(ServerPlayer p) {
        for (Entity e : snapshot.level.getEntities(p, area)) if (!originals.contains(e.getUUID())) e.discard();
        for (BlockPos pos : snapshot.blocks.keySet()) {
            BlockState expected = pos.getY() == floorY ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (snapshot.level.getBlockState(pos) != expected) snapshot.level.setBlock(pos, expected, 3);
        }
        p.teleportTo(playerPosition.x, playerPosition.y, playerPosition.z); p.setDeltaMovement(Vec3.ZERO);
        if (focusedFreelookItem != null) p.hurtMarked = true;
    }

    private static void acknowledge(Minecraft mc) {
        acknowledgement = null; token++;
        mc.player.connection.sendCommand(ACK + " " + token);
    }

    private static Observation observe(ServerPlayer p, int request) {
        try {
            ItemStack stack = p.getMainHandItem();
            String held = stackSignature(stack);
            Map<String, Integer> entities = new TreeMap<>();
            for (Entity e : p.serverLevel().getEntities(p, area)) if (!originals.contains(e.getUUID()))
                entities.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), 1, Integer::sum);
            List<String> effects = p.getActiveEffects().stream().map(e -> e.getEffect().getRegisteredName() + ":" + e.getAmplifier()).sorted().toList();
            return new Observation(request, held, p.isUsingItem(), p.containerMenu.getClass().getName(),
                    effects.toString(), curiosSummary(p), equipmentSummary(p), entities.toString(), p.serverLevel().dimension().location().toString(), "");
        } catch (Throwable failure) { return new Observation(request, "", false, "", "", "", "", "", "", failure.toString()); }
    }

    private static boolean emptyExceptMainHand(net.minecraft.world.entity.player.Player player) {
        for (int i = 1; i < player.getInventory().getContainerSize(); i++)
            if (!player.getInventory().getItem(i).isEmpty()) return false;
        return true;
    }

    private static String equipmentSummary(net.minecraft.world.entity.player.Player player) {
        Map<String, String> result = new TreeMap<>();
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot == net.minecraft.world.entity.EquipmentSlot.MAINHAND) continue;
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) result.put(slot.getName(), stackSignature(stack));
        }
        return result.toString();
    }

    private static void timeoutDiagnostics(Minecraft mc) {
        String context = "item=" + id() + " mode=" + mode + " phase=" + phase;
        try {
            log.accept("ITEM_AIR_USE TIMEOUT_CLIENT " + context + " expected=" + stackSignature(expectedDefault(items.get(index)))
                    + " taskDone=" + (task != null && task.isDone()) + " activeAbilities=" + activeAbilities + " " + playerDiagnostic(mc.player));
        } catch (Throwable error) { log.accept("ITEM_AIR_USE TIMEOUT_CLIENT " + context + " diagnosticError=" + error); }
        server(mc, p -> {
            try { log.accept("ITEM_AIR_USE TIMEOUT_SERVER " + context + " activeAbilities=" + SimplySwordsQaIsolation.active(p) + " " + playerDiagnostic(p)); }
            catch (Throwable error) { log.accept("ITEM_AIR_USE TIMEOUT_SERVER " + context + " diagnosticError=" + error); }
        });
    }

    private static String playerDiagnostic(net.minecraft.world.entity.player.Player player) {
        Map<Integer, String> inventory = new TreeMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) inventory.put(i, stackSignature(stack));
        }
        return "held=" + stackSignature(player.getMainHandItem()) + " selected=" + player.getInventory().selected
                + " food=" + player.getFoodData().getFoodLevel() + " curios=" + curiosSummary(player)
                + " equipment=" + equipmentSummary(player) + " inventory=" + inventory
                + " position=" + player.position() + " arenaDistanceSquared=" + player.position().distanceToSqr(playerPosition)
                + " using=" + player.isUsingItem() + " dimension=" + player.level().dimension().location();
    }

    private static String stackSignature(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + ":count=" + stack.getCount() + ":damage=" + stack.getDamageValue();
    }

    private static Observation withRuntimePacketErrors(Observation observation) throws Exception {
        // PacketUtils suppresses server item exceptions, so command acknowledgments
        // alone cannot certify successful use. Read only this view's log window;
        // unrelated world-generation exceptions do not qualify.
        String window;
        try (var file = new java.io.RandomAccessFile(Path.of("logs", "latest.log").toFile(), "r")) {
            long start = file.length() < runtimeLogOffset ? 0 : runtimeLogOffset;
            int length = Math.toIntExact(Math.min(file.length() - start, 2_000_000));
            byte[] bytes = new byte[length]; file.seek(start); file.readFully(bytes);
            window = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        List<String> packetErrors = new ArrayList<>();
        String[] lines = window.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("Failed to handle packet")
                    && (lines[i].contains("ServerboundUseItemPacket") || lines[i].contains("ServerboundPlayerActionPacket")))
                packetErrors.add(i + 1 < lines.length ? lines[i + 1].trim() : lines[i].trim());
        }
        if (packetErrors.isEmpty()) return observation;
        String context = "item=" + id() + " mode=" + (mode == 0 ? "first_person" : secondaryMode());
        Files.writeString(evidenceRoot.resolve("runtime-packet-errors.log"), "\nITEM_AIR_USE " + context + "\n" + window,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String error = "runtime_packet_exceptions=" + packetErrors;
        log.accept("ITEM_AIR_USE SERVER_PACKET_ERROR " + context + " " + error);
        return new Observation(observation.token, observation.held, observation.using, observation.menu,
                observation.effects, observation.curios, observation.equipment, observation.entities,
                observation.dimension, observation.error + error);
    }

    private static ItemStack expectedDefault(Item item) throws Exception {
        ItemStack stack = item.getDefaultInstance();
        // Installed Simply Swords retains legacy registry aliases which migrate
        // into component-based modern stacks on their first server inventory tick.
        try {
            stack = (ItemStack) Class.forName("net.sweenus.simplyswords.util.LegacyUniqueMigration")
                    .getMethod("convertStack", ItemStack.class).invoke(null, stack);
        } catch (ClassNotFoundException absent) { /* optional alias migration */ }
        // A default "bound" Storage Drawers upgrade has no controller binding.
        // Its public inventory validator converts it to the unbound entry every
        // 60 server ticks. Wait for that actual conversion; do not replace the
        // raw equipped fixture or pretend that controller binding was tested.
        if (item.getClass().getName().equals("com.jaquadro.minecraft.storagedrawers.item.ItemUpgradeRemote"))
            stack = (ItemStack) item.getClass().getMethod("setUnbound", ItemStack.class).invoke(null, stack);
        return stack;
    }

    private static boolean autoConsumedBeforeUse(Item item) {
        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("dev.lopyluna.dndesires.content.items.packages.BurstPackageItem")) return true;
        return false;
    }

    private static Observation combine(Observation a, Observation b) {
        return new Observation(b.token, a.held + " -> " + b.held, a.using || b.using,
                a.menu + " -> " + b.menu, a.effects + " -> " + b.effects,
                a.curios + " -> " + b.curios, a.equipment + " -> " + b.equipment,
                a.entities + " -> " + b.entities, a.dimension + " -> " + b.dimension, a.error + b.error);
    }

    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        UUID playerId = mc.player.getUUID();
        return mc.getSingleplayerServer().submit(() -> {
            ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("QA server player absent");
            action.accept(player);
        }).whenComplete((ignored, error) -> {
            if (error != null) log.accept("ITEM_AIR_USE SERVER_ERROR " + error);
        });
    }

    private static void finish(Minecraft mc, String result) throws Exception {
        running = false;
        mc.gameMode.releaseUsingItem(mc.player); mc.setScreen(null);
        if (snapshot != null) server(mc, p -> {
            p.stopUsingItem(); p.stopRiding(); p.closeContainer();
            clearCurios(p);
            Object curios = curiosHandler(p);
            if (curios != null && originalCurios != null) {
                try { loadCurios.invoke(curios, originalCurios.copy()); }
                catch (Exception e) { throw new IllegalStateException("Curios restoration failed", e); }
            }
            if (originals != null) for (Entity e : snapshot.level.getEntities(p, area))
                if (!originals.contains(e.getUUID())) e.discard();
            for (Map.Entry<BlockPos, SavedBlock> entry : snapshot.blocks.entrySet()) {
                snapshot.level.setBlock(entry.getKey(), entry.getValue().state, 3);
                var be = snapshot.level.getBlockEntity(entry.getKey());
                if (be != null && entry.getValue().data != null) be.loadWithComponents(entry.getValue().data, p.registryAccess());
            }
            p.getInventory().clearContent();
            for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.selected;
            p.setGameMode(snapshot.mode);
            p.removeAllEffects(); for (MobEffectInstance effect : snapshot.effects) p.addEffect(new MobEffectInstance(effect));
            p.setHealth(snapshot.health); p.getFoodData().setFoodLevel(snapshot.food); p.getFoodData().setSaturation(snapshot.saturation);
            p.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
            p.setYRot(snapshot.yaw); p.setXRot(snapshot.pitch); p.containerMenu.broadcastChanges();
            log.accept("ITEM_AIR_USE RESTORED server fixture and player");
        });
        for (Map.Entry<Field, Object> entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(toggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(shoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(amount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(max);
        mc.options.setCameraType(cameraType); mc.options.pauseOnLostFocus = pausedOnFocus;
        freelookKey.setDown(oldFreelook);
        close();
        log.accept("ITEM_AIR_USE " + result + " completed=" + completed + " scheduled=" + items.size()
                + " differences=" + differences + " errors=" + errors + " full_ability_success=NOT_CERTIFIED");
    }

    private static void ensureIdentity() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("ItemUseProbe-v1-default-air-mainhand-40ticks".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(("profile=" + (freelookProfile ? "freelook" : "offset")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var mod : net.neoforged.fml.ModList.get().getMods().stream().sorted(Comparator.comparing(m -> m.getModId())).toList())
            digest.update((mod.getModId() + "=" + mod.getVersion() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var stream = UnlockedCameraClient.class.getResourceAsStream("UnlockedCameraClient.class")) { digest.update(stream.readAllBytes()); }
        try (var stream = ItemUseProbe.class.getResourceAsStream("ItemUseProbe.class")) { digest.update(stream.readAllBytes()); }
        try (var stream = SimplySwordsQaIsolation.class.getResourceAsStream("SimplySwordsQaIsolation.class")) { digest.update(stream.readAllBytes()); }
        String identity = HexFormat.of().formatHex(digest.digest());
        Path file = evidenceRoot.resolve("identity.txt");
        if (Files.exists(file) && !Files.readString(file).trim().equals(identity))
            throw new IllegalStateException("Item-use resume identity differs; preserve/rename old evidence before rerun");
        Files.writeString(file, identity + System.lineSeparator());
    }

    private static Object curiosHandler(LivingEntity player) {
        if (getCurios == null) return null;
        try { return ((Optional<?>) getCurios.invoke(null, player)).orElse(null); }
        catch (Exception e) { throw new IllegalStateException("Curios QA bridge failed", e); }
    }
    private static ListTag clearCurios(LivingEntity player) {
        Object handler = curiosHandler(player);
        if (handler == null) return null;
        try { return ((ListTag) saveCurios.invoke(handler, true)).copy(); }
        catch (Exception e) { throw new IllegalStateException("Curios snapshot/clear failed", e); }
    }
    private static String curiosSummary(LivingEntity player) {
        Object handler = curiosHandler(player);
        Map<String, Integer> equipped = new TreeMap<>();
        if (handler != null) {
            try {
                IItemHandler inventory = (IItemHandler) equippedCurios.invoke(handler);
                for (int i = 0; i < inventory.getSlots(); i++) {
                    ItemStack stack = inventory.getStackInSlot(i);
                    if (!stack.isEmpty()) equipped.merge(stackSignature(stack), 1, Integer::sum);
                }
            } catch (Exception e) { throw new IllegalStateException("Curios observation failed", e); }
        }
        return equipped.toString();
    }

    private static String id() { return index < items.size() ? BuiltInRegistries.ITEM.getKey(items.get(index)).toString() : "complete"; }
    private static String secondaryMode() { return freelookProfile ? "freelook" : "shoulder"; }
    private static String idPrevious() { return index > 0 ? BuiltInRegistries.ITEM.getKey(items.get(index - 1)).toString() : "none"; }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void row(Object... cells) throws Exception {
        for (int i = 0; i < cells.length; i++) { if (i > 0) writer.write('\t'); writer.write(String.valueOf(cells[i]).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')); }
        writer.newLine();
    }
    private static void close() { try { if (writer != null) writer.close(); } catch (Exception ignored) {} }
}
