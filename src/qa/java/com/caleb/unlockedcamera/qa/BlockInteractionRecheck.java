package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Separate failed-only request recheck with settled fixtures and explicit server readiness. */
public final class BlockInteractionRecheck {
    public static final String FIXTURE_VERSION = "fresh-support20-teleport-ready-hold-release-v1";
    private static final Vec3 PLAYER = new Vec3(1742.5, 150, 190.5);
    private static final BlockPos TARGET = new BlockPos(1742, 150, 193);
    private static final BoundingBox BOX = new BoundingBox(1738, 149, 185, 1746, 156, 198);
    private static final AABB AREA = new AABB(1738, 149, 185, 1747, 157, 199);
    private static final String COMMAND = "unlockedcamera_qa_block_recheck";
    private static final int REPEATS = 3;
    private static boolean running, registered;
    private static int index, mode, repeat, phase, age, token;
    private static volatile int serverUses;
    private static volatile Observation acknowledgement;
    private static volatile Snapshot saved;
    private static CompletableFuture<Void> task;
    private static List<String> ids;
    private static Map<Field, Object> fields;
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static CameraType oldType;
    private static boolean oldToggle, oldShoulder;
    private static double oldMax;
    private static Observation before, firstBefore, firstAfter;
    private static String picked, use, firstPick, firstUse, hit, firstHit;
    private static Method picker, cameraPosition, cameraRotation;
    private static Field pendingTeleport;
    private record SavedBlock(BlockState state, CompoundTag data) {}
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType gameMode,
                            int selected, List<ItemStack> inventory, Map<BlockPos, SavedBlock> blocks, Set<UUID> entities) {}
    private record Observation(int token, boolean ready, String state, String menu, String vehicle,
                               String blockEntity, String support, String entities, int events, String error) {
        String signature() { return state + "|" + menu + "|" + vehicle + "|" + blockEntity; }
    }
    private BlockInteractionRecheck() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || BlockInteractionProbe.isRunning()) throw new IllegalStateException("Wait for the live block sweep to finish");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Requires isolated integrated world");
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Restricted to disposable QA instances");
        Path source = game.getParent().getParent().getParent().resolve("build/modpack-block-test/game/qa/block-interactions.tsv");
        Set<String> selected = new LinkedHashSet<>();
        for (String line : Files.readAllLines(source)) {
            String[] row = line.split("\t", -1);
            if (row.length > 2 && Set.of("OBSERVATION_DIFF", "TARGETING_FAIL", "CONTEXT_DIFFERENCE", "ERROR").contains(row[1]))
                selected.add(row[0]);
        }
        if (selected.size() > 500) throw new IllegalStateException("Narrow recheck limited to 500 differing blocks");
        log = output;
        Path evidence = Path.of("qa", "block-interaction-recheck.tsv");
        Path version = Path.of("qa", "block-interaction-recheck-fixture-version.txt");
        String fixtureIdentity = BlockReviewIdentity.fingerprint();
        int reused = 0;
        if (Files.exists(version) && Files.readString(version).trim().equals(fixtureIdentity)) {
            Map<String, Set<String>> completedPairs = new LinkedHashMap<>();
            List<Path> previous = new ArrayList<>();
            try (var files = Files.list(Path.of("qa"))) {
                files.filter(p -> p.getFileName().toString().startsWith("block-interaction-recheck")
                        && p.getFileName().toString().endsWith(".tsv")).forEach(previous::add);
            }
            for (Path path : previous) {
                Path fileVersion = path.equals(evidence) ? version : Path.of(path + ".fixture-version.txt");
                if (!Files.exists(fileVersion) || !Files.readString(fileVersion).trim().equals(fixtureIdentity)) continue;
                for (String line : Files.readAllLines(path)) {
                    String[] cells = line.split("\t", -1);
                    if (cells.length >= 16 && !cells[0].equals("block") && cells[15].contains("freshFixture=true;settleTicks=20"))
                        completedPairs.computeIfAbsent(cells[0], ignored -> new LinkedHashSet<>()).add(cells[1]);
                }
            }
            int beforeCount = selected.size();
            selected.removeIf(id -> completedPairs.getOrDefault(id, Set.of()).containsAll(Set.of("0", "1", "2")));
            reused = beforeCount - selected.size();
        }
        ids = List.copyOf(selected);
        if (Files.exists(evidence)) {
            Path archive = Path.of("qa", "block-interaction-recheck-previous-" + System.currentTimeMillis() + ".tsv");
            Files.copy(evidence, archive);
            if (Files.exists(version)) Files.copy(version, Path.of(archive + ".fixture-version.txt"));
        }
        Files.writeString(version, fixtureIdentity);
        writer = Files.newBufferedWriter(evidence);
        writer.write("block\trepeat\tstatus\tfirst_pick\tfirst_use\tfirst_before\tfirst_after\tfirst_server_events\tshoulder_pick\tshoulder_use\tshoulder_before\tshoulder_after\tshoulder_server_events\tfirst_hit\tshoulder_hit\tdiagnostics\n");
        fields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(name); fields.put(field, field.get(null));
        }
        oldType = mc.options.getCameraType(); oldToggle = ClientConfig.TOGGLE_MOD.get();
        oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        picker = BlockInteractionProbe.class.getDeclaredMethod("pick", Minecraft.class, int.class); picker.setAccessible(true);
        cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class); cameraPosition.setAccessible(true);
        cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); cameraRotation.setAccessible(true);
        pendingTeleport = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
        pendingTeleport.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(BlockInteractionRecheck::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, BlockInteractionRecheck::used);
            registered = true;
        }
        index = mode = repeat = phase = age = token = 0; running = true;
        mc.setScreen(null); mc.options.pauseOnLostFocus = false;
        log.accept("BLOCK_RECHECK START blocks=" + ids.size() + " repeats=" + REPEATS + " reusedCompleted=" + reused + " fixtureVersion=" + FIXTURE_VERSION + " source=" + source
                + " freshSupport=true settledBeforeState=true teleportReadyRequired=true");
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
            mc.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(COMMAND)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        if (running && mc.player != null && player.getUUID().equals(mc.player.getUUID()))
                            acknowledgement = observe(player, IntegerArgumentType.getInteger(context, "token"));
                        return 1;
                    })));
            p.stopRiding(); p.closeContainer(); p.setGameMode(GameType.CREATIVE);
            p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
        });
    }

    private static void used(PlayerInteractEvent.RightClickBlock event) {
        if (running && !event.getLevel().isClientSide() && event.getPos().equals(TARGET)
                && Minecraft.getInstance().player != null
                && event.getEntity().getUUID().equals(Minecraft.getInstance().player.getUUID())) serverUses++;
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.level == null) throw new IllegalStateException("Disconnected");
            if (++age > 400) throw new IllegalStateException("Timeout phase=" + phase + " item=" + ids.get(index));
            if (mc.screen != null && mc.screen.isPauseScreen()) mc.setScreen(null);
            switch (phase) {
                case 0 -> {
                    if (!task.isDone() || age < 25) return;
                    task.join(); prepare(mc);
                }
                case 1 -> {
                    if (!task.isDone() || age < 20) return;
                    task.join();
                    if (mc.player.position().distanceToSqr(PLAYER) > .01) {
                        if (age % 20 == 0) {
                            log.accept("BLOCK_RECHECK POSITION_RECOVERY item=" + ids.get(index) + " mode=" + mode + " client=" + mc.player.position());
                            releaseHold(); mc.player.setDeltaMovement(Vec3.ZERO);
                            task = server(mc, p -> { p.setDeltaMovement(Vec3.ZERO); p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z); });
                        }
                        return;
                    }
                    request(mc); phase = 2; age = 0;
                }
                case 2 -> {
                    Observation observed = acknowledgement;
                    if (observed == null || observed.token != token) return;
                    if (!observed.error.isEmpty()) throw new IllegalStateException(observed.error);
                    if (!observed.ready) { request(mc); return; }
                    // Match the authoritative settled state, not a stale expected default.
                    if (!mc.level.getBlockState(TARGET).toString().equals(observed.state)) { request(mc); return; }
                    before = observed;
                    send(mc); request(mc); phase = 3; age = 0;
                }
                case 3 -> {
                    Observation after = acknowledgement;
                    if (after == null || after.token != token) return;
                    if (!after.error.isEmpty()) throw new IllegalStateException(after.error);
                    if (mode == 0) {
                        firstBefore = before; firstAfter = after; firstPick = picked; firstUse = use; firstHit = hit;
                        mode = 1;
                    } else {
                        result(after); mode = 0;
                        if (++repeat >= REPEATS) { repeat = 0; index++; }
                    }
                    if (index >= ids.size()) finish(mc, "DONE"); else prepare(mc);
                }
                default -> throw new IllegalStateException("Bad phase " + phase);
            }
        } catch (Throwable error) {
            log.accept("BLOCK_RECHECK ERROR index=" + index + " mode=" + mode + " " + error);
            try { finish(mc, "ABORT"); } catch (Throwable restore) { log.accept("BLOCK_RECHECK RESTORE_ERROR " + restore); }
        }
    }

    private static void prepare(Minecraft mc) throws Exception {
        if (ids.isEmpty()) { finish(mc, "DONE"); return; }
        releaseHold();
        mc.player.closeContainer(); mc.setScreen(null); mc.player.getInventory().clearContent();
        mc.player.setDeltaMovement(Vec3.ZERO); mc.player.resetFallDistance();
        task = server(mc, p -> {
            if (p.serverLevel() != saved.level) throw new IllegalStateException("DIMENSION_CHANGED");
            p.stopRiding(); p.closeContainer();
            for (Entity e : saved.level.getEntities(p, AREA)) if (!saved.entities.contains(e.getUUID())) e.discard();
            saved.level.getBlockTicks().clearArea(BOX); saved.level.getFluidTicks().clearArea(BOX);
            // Clear all prior neighbors and restore support every mode. This is a disposable fixture only.
            for (BlockPos pos : saved.blocks.keySet()) saved.level.setBlock(pos,
                    pos.getY() == 149 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            for (Entity e : saved.level.getEntities(p, AREA)) if (!saved.entities.contains(e.getUUID())) e.discard();
            p.getInventory().clearContent(); p.getInventory().selected = 0;
            p.setDeltaMovement(Vec3.ZERO); p.resetFallDistance();
            if (p.position().distanceToSqr(PLAYER) > .001) p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
            p.setYRot(0); p.setXRot(0); p.containerMenu.broadcastChanges();
            BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(ids.get(index))).defaultBlockState();
            if (state.hasProperty(BlockStateProperties.ATTACH_FACE) && state.getValue(BlockStateProperties.ATTACH_FACE) == AttachFace.WALL
                    && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                saved.level.setBlock(TARGET.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()), Blocks.STONE.defaultBlockState(), 3);
            saved.level.setBlock(TARGET, state, 3); serverUses = 0;
        });
        phase = 1; age = 0;
    }

    private static void request(Minecraft mc) {
        acknowledgement = null;
        mc.player.connection.sendCommand(COMMAND + " " + ++token);
    }

    private static void send(Minecraft mc) throws Exception {
        Object pick = picker.invoke(null, mc, mode == 0 ? 0 : 1);
        picked = (String) member(pick, "status"); use = "NOT_SENT"; hit = "none";
        BlockHitResult selected = (BlockHitResult) member(pick, "hit");
        if (selected == null) return;
        hit = selected.getDirection() + ":" + selected.getLocation();
        ClientConfig.TOGGLE_MOD.set(mode != 0); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        field("active").set(null, mode != 0); field("targetDistance").set(null, 4f);
        mc.options.setCameraType(mode == 0 ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 oldPos = camera.getPosition(); float yaw = camera.getYRot(), pitch = camera.getXRot(), roll = camera.getRoll();
        float targetYaw = (float) member(pick, "yaw"), targetPitch = (float) member(pick, "pitch");
        try {
            cameraPosition.invoke(camera, member(pick, "origin")); cameraRotation.invoke(camera, targetYaw, targetPitch, 0f);
            float bodyYaw = mode == 0 ? targetYaw : 0f, bodyPitch = mode == 0 ? targetPitch : 0f;
            mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw; mc.player.setXRot(bodyPitch); mc.player.xRotO = bodyPitch;
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(bodyYaw, bodyPitch, mc.player.onGround()));
            mc.hitResult = selected;
            use = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, selected).toString();
        } finally { cameraPosition.invoke(camera, oldPos); cameraRotation.invoke(camera, yaw, pitch, roll); }
    }

    private static Observation observe(ServerPlayer p, int requested) {
        try {
            if (p.serverLevel() != saved.level) throw new IllegalStateException("DIMENSION_CHANGED");
            BlockEntity be = saved.level.getBlockEntity(TARGET);
            String entities = saved.level.getEntities(p, AREA).stream().map(e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()).sorted().toList().toString();
            return new Observation(requested, pendingTeleport.get(p.connection) == null,
                    saved.level.getBlockState(TARGET).toString(), p.containerMenu == p.inventoryMenu ? "inventory" : p.containerMenu.getClass().getName(),
                    p.getVehicle() == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString(),
                    be == null ? "none" : be.getClass().getName(), saved.level.getBlockState(TARGET.below()).toString(), entities, serverUses, "");
        } catch (Throwable error) { return new Observation(requested, false, "", "", "", "", "", "", serverUses, error.toString()); }
    }

    private static void result(Observation after) throws Exception {
        String status;
        int firstEvents = firstAfter.events - firstBefore.events, sideEvents = after.events - before.events;
        if (!firstBefore.signature().equals(before.signature())) status = "UNEQUAL_SETTLED_FIXTURE";
        else if (!firstPick.equals("TARGET") || !picked.equals("TARGET")) status = "NEEDS_SPECIALIZED_FIXTURE";
        else if (firstEvents == 0 || sideEvents == 0) status = "SERVER_ACTION_NOT_OBSERVED";
        else if (!firstAfter.signature().equals(after.signature())) status = "SERVER_DIFFERENCE_REVIEW";
        else if (!firstUse.equals(use)) status = "MATCHED_SERVER_LOCAL_RETURN_DIFF";
        else if (!firstAfter.signature().equals(firstBefore.signature())) status = "OBSERVED_PARITY_PASS";
        else status = "MATCHED_NO_OBSERVABLE_EFFECT";
        String detail = "teleportReady=" + firstBefore.ready + "/" + before.ready + ";support=" + firstBefore.support + "/" + before.support
                + ";entities=" + firstBefore.entities + "/" + before.entities + ";freshFixture=true;settleTicks=20;rightClickEventReceiptOnly";
        writer.write(String.join("\t", ids.get(index), String.valueOf(repeat), status, firstPick, firstUse, firstBefore.signature(), firstAfter.signature(),
                String.valueOf(firstEvents), picked, use, before.signature(), after.signature(), String.valueOf(sideEvents), firstHit, hit, clean(detail)) + "\n");
        writer.flush();
        log.accept("BLOCK_RECHECK " + ids.get(index) + " repeat=" + repeat + " " + status + " serverEvents=" + firstEvents + "/" + sideEvents);
    }

    private static void finish(Minecraft mc, String status) throws Exception {
        running = false; writer.close(); mc.player.closeContainer(); mc.setScreen(null);
        releaseHold(); mc.player.setDeltaMovement(Vec3.ZERO);
        for (Map.Entry<Field, Object> entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax); mc.options.setCameraType(oldType);
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
            p.getInventory().selected = original.selected; p.setGameMode(original.gameMode);
            p.teleportTo(original.level, original.position.x, original.position.y, original.position.z, original.yaw, original.pitch);
            p.containerMenu.broadcastChanges();
        }).thenRun(() -> log.accept("BLOCK_RECHECK RESTORED"));
        log.accept("BLOCK_RECHECK " + status + " blocks=" + index + "/" + ids.size() + " repeats=" + REPEATS);
    }

    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer(); UUID id = mc.player.getUUID();
        return CompletableFuture.runAsync(() -> action.accept(server.getPlayerList().getPlayer(id)), server);
    }
    private static Object member(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(record);
    }
    private static void releaseHold() throws Exception {
        try {
            Class<?> manager = Class.forName("dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager");
            if ((boolean) manager.getMethod("isActive").invoke(null)) {
                manager.getMethod("stop").invoke(null);
                log.accept("BLOCK_RECHECK FIXTURE_RELEASE simulated hold interaction stopped between cases");
            }
        } catch (ClassNotFoundException absent) { /* Optional installed integration. */ }
    }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static String clean(String value) { return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
}
