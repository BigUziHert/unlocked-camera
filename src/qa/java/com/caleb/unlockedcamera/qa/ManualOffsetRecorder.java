package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.caleb.unlockedcamera.client.ClientConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Opt-in observation only. No input, camera, player, inventory, packet or world mutations. */
public final class ManualOffsetRecorder {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_WATCHES = 64, MAX_RECORDS = 100_000, MAX_ACTIONS = 5_000;
    private static final long MAX_CHARACTERS = 200_000_000;
    private static final Set<Integer> SAMPLE_TICKS = Set.of(1, 2, 5, 10, 20, 40, 80);
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<ArrayDeque<Long>> CHAINS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Long>> CALLS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<ServerContext>> SERVER = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<Integer, PacketLink> PACKETS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<Integer> PACKET_ORDER = new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<Watch> CLIENT_WATCHES = new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<Watch> SERVER_WATCHES = new ConcurrentLinkedDeque<>();
    private static volatile boolean active;
    private static boolean registered;
    private static volatile UUID actor;
    private static volatile Connection connection;
    private static BufferedWriter writer;
    private static Consumer<String> output;
    private static int records, actions, clientTick;
    private static long characters;
    private static final Map<String, Field> MODE_FIELDS = new LinkedHashMap<>();
    private static Method freelookPredicate;
    private record PacketLink(long chain, long call, String kind, InteractionHand hand, BlockPos pos) {}
    private record ServerContext(long observation, int sequence, PacketLink link, BlockPos pos) {}
    private static final class Watch {
        final long id; final Player player; final Level level; final BlockPos pos; final int start;
        String signature;
        Watch(long id, Player player, BlockPos pos, int start) {
            this.id = id; this.player = player; this.level = player.level(); this.pos = pos; this.start = start;
            signature = JSON.toJson(world(level, pos));
        }
    }
    private ManualOffsetRecorder() {}

    /** Existing request API: probe ManualOffsetRecorder. Does not move/close/change anything. */
    public static synchronized void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (active) throw new IllegalStateException("Manual recorder already running");
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Manual recorder is restricted to disposable QA copies");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Manual recorder requires a loaded integrated QA world");
        Path dir = game.resolve("qa/manual-offset/run-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        writer = Files.newBufferedWriter(dir.resolve("events.jsonl"));
        output = log; actor = mc.player.getUUID(); connection = mc.getConnection().getConnection();
        records = actions = clientTick = 0; characters = 0; IDS.set(0);
        if (MODE_FIELDS.isEmpty()) {
            for (String name : new String[]{"targetDistance", "smoothedDistance", "shoulderSide", "shoulderOffset", "freelookYaw", "freelookPitch"}) {
                Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); MODE_FIELDS.put(name, f);
            }
            freelookPredicate = UnlockedCameraClient.class.getDeclaredMethod("isFreelookHeld", Minecraft.class);
            freelookPredicate.setAccessible(true);
        }
        PACKETS.clear(); PACKET_ORDER.clear(); CLIENT_WATCHES.clear(); SERVER_WATCHES.clear();
        CHAINS.remove(); CALLS.remove();
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, ManualOffsetRecorder::inputBeginning);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ManualOffsetRecorder::inputEnding);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, ManualOffsetRecorder::blockBeginning);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ManualOffsetRecorder::blockEnding);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ManualOffsetRecorder::placement);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ManualOffsetRecorder::entityJoined);
            NeoForge.EVENT_BUS.addListener(ManualOffsetRecorder::clientTick);
            NeoForge.EVENT_BUS.addListener(ManualOffsetRecorder::serverTick);
            registered = true;
        }
        active = true;
        emit("START", 0, map("file", dir.resolve("events.jsonl").toString(), "actor", actor.toString(),
                "client", client(mc), "scope", "passive_actual_manual_input;no_automatic_pass_judgments;client_and_server_samples_are_not_simultaneous",
                "limits", "nearby_glue_not_proof_of_creation_by_this_click;other_actions_can_overlap;64_watches_per_side;80_tick_windows;5000_calls;100000_records;200MB"));
        log.accept("MANUAL_OFFSET_RECORDER START file=" + dir.resolve("events.jsonl"));
    }

    public static synchronized void stop(Minecraft mc, Consumer<String> log) {
        if (!active) { log.accept("MANUAL_OFFSET_RECORDER NOT_RUNNING"); return; }
        emit("STOP", 0, map("pendingClientWindows", CLIENT_WATCHES.size(), "pendingServerWindows", SERVER_WATCHES.size(),
                "records", records, "calls", actions, "scope", "no_state_was_changed_by_recorder;pending_windows_are_truncated"));
        close(); log.accept("MANUAL_OFFSET_RECORDER STOPPED records=" + records + " calls=" + actions);
    }

    public static long beginChain() {
        if (!active) return 0;
        long id = IDS.incrementAndGet(); CHAINS.get().push(id);
        safely(() -> emit("INPUT_CHAIN_BEGIN", id, client(Minecraft.getInstance())));
        return id;
    }
    public static void endChain(long id, Throwable error) {
        if (id == 0) return;
        safely(() -> emit("INPUT_CHAIN_END", id, map("client", client(Minecraft.getInstance()), "exception", error == null ? "none" : error.toString())));
        pop(CHAINS.get(), id);
    }
    public static long beginUse(String kind, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!active || !player.getUUID().equals(actor)) return 0;
        if (++actions > MAX_ACTIONS) { limit("maximum actions"); return 0; }
        long id = IDS.incrementAndGet(); CALLS.get().push(id);
        safely(() -> {
            emit("CLIENT_CALL_BEGIN", id, map("chain", peek(CHAINS.get()), "kind", kind, "hand", hand.name(),
                    "requestedHit", describeHit(hit), "client", client(Minecraft.getInstance()),
                    "observation", snapshot(player, hit == null ? currentTarget() : hit.getBlockPos())));
            addWatch(CLIENT_WATCHES, new Watch(id, player, hit == null ? currentTarget() : hit.getBlockPos(), clientTick));
        });
        return id;
    }
    public static void endUse(long id, Player player, BlockHitResult hit, Object result, Throwable error) {
        if (id == 0) return;
        safely(() -> emit("CLIENT_CALL_RETURN", id, map("chain", peek(CHAINS.get()), "result", String.valueOf(result),
                "exception", error == null ? "none" : error.toString(), "client", client(Minecraft.getInstance()),
                "observation", snapshot(player, hit == null ? currentTarget() : hit.getBlockPos()))));
        pop(CALLS.get(), id);
    }

    /** Called after ClientCommonPacketListener's production packet substitutions. No packets are generated. */
    public static void outgoing(Connection source, Packet<?> packet) {
        if (!active || source != connection) return;
        if (!(packet instanceof ServerboundUseItemOnPacket) && !(packet instanceof ServerboundUseItemPacket)) return;
        safely(() -> {
            int sequence; InteractionHand hand; BlockPos pos; String kind; Map<String, Object> details;
            if (packet instanceof ServerboundUseItemOnPacket p) {
                sequence = p.getSequence(); hand = p.getHand(); pos = p.getHitResult().getBlockPos(); kind = "UseItemOn";
                details = map("hit", describeHit(p.getHitResult()));
            } else {
                ServerboundUseItemPacket p = (ServerboundUseItemPacket) packet;
                sequence = p.getSequence(); hand = p.getHand(); pos = currentTarget(); kind = "UseItem";
                details = map("yaw", p.getYRot(), "pitch", p.getXRot());
            }
            PacketLink link = new PacketLink(peek(CHAINS.get()), peek(CALLS.get()), kind, hand, pos);
            PACKETS.put(sequence, link); PACKET_ORDER.add(sequence);
            while (PACKET_ORDER.size() > 2048) { Integer old = PACKET_ORDER.poll(); if (old != null) PACKETS.remove(old); }
            emit("OUTGOING_USE_PACKET", link.call, map("sequence", sequence, "chain", link.chain, "kind", kind,
                    "hand", hand.name(), "details", details, "connected", source.isConnected(),
                    "scope", "observed_connection_send_not_an_acknowledgement"));
        });
    }

    /** Integrated server thread only; netty scheduling entry is deliberately ignored. */
    public static long serverBegin(ServerPlayer player, int sequence, InteractionHand hand, BlockHitResult hit) {
        if (!active || !player.getUUID().equals(actor) || !player.server.isSameThread()) return 0;
        long id = IDS.incrementAndGet(); PacketLink link = PACKETS.get(sequence);
        BlockPos pos = hit != null ? hit.getBlockPos() : link == null ? null : link.pos;
        SERVER.get().push(new ServerContext(id, sequence, link, pos));
        safely(() -> emit("SERVER_PACKET_BEGIN", id, map("sequence", sequence, "clientCall", link == null ? 0 : link.call,
                "chain", link == null ? 0 : link.chain, "hand", hand.name(), "hit", describeHit(hit), "observation", snapshot(player, pos))));
        return id;
    }
    public static void serverEnd(long id, ServerPlayer player, Throwable error) {
        if (id == 0) return;
        ServerContext context = SERVER.get().peek();
        safely(() -> {
            emit("SERVER_PACKET_RETURN", id, map("sequence", context.sequence,
                    "exception", error == null ? "none" : error.toString(), "observation", snapshot(player, context.pos),
                    "scope", "handler_return_not_automatic_success"));
            addWatch(SERVER_WATCHES, new Watch(id, player, context.pos, player.server.getTickCount()));
        });
        if (!SERVER.get().isEmpty()) SERVER.get().pop();
    }

    private static void inputBeginning(InputEvent.InteractionKeyMappingTriggered e) { input(e, "INPUT_EVENT_BEGIN"); }
    private static void inputEnding(InputEvent.InteractionKeyMappingTriggered e) { input(e, "INPUT_EVENT_END"); }
    private static void input(InputEvent.InteractionKeyMappingTriggered e, String label) {
        if (active && e.isUseItem()) safely(() -> emit(label, peek(CHAINS.get()), map("hand", e.getHand().name(),
                "canceled", e.isCanceled(), "swing", e.shouldSwingHand(), "client", client(Minecraft.getInstance()))));
    }
    private static void blockBeginning(PlayerInteractEvent.RightClickBlock e) { block(e, "RIGHT_CLICK_BLOCK_BEGIN"); }
    private static void blockEnding(PlayerInteractEvent.RightClickBlock e) { block(e, "RIGHT_CLICK_BLOCK_EVENT_END"); }
    private static void block(PlayerInteractEvent.RightClickBlock e, String label) {
        if (!active || !e.getEntity().getUUID().equals(actor)) return;
        safely(() -> {
            ServerContext s = SERVER.get().peek();
            emit(label, e.getLevel().isClientSide ? peek(CALLS.get()) : s == null ? 0 : s.observation,
                    map("side", e.getLevel().isClientSide ? "client" : "server", "sequence", s == null ? -1 : s.sequence,
                            "hand", e.getHand().name(), "pos", e.getPos().toShortString(), "hit", describeHit(e.getHitVec()),
                            "canceled", e.isCanceled(), "cancellationResult", e.getCancellationResult().toString(),
                            "useBlock", e.getUseBlock().toString(), "useItem", e.getUseItem().toString(),
                            "observation", snapshot(e.getEntity(), e.getPos()), "scope", "event_dispatch_observation_before_block_use_returns"));
        });
    }
    private static void placement(BlockEvent.EntityPlaceEvent e) {
        if (!active || e.getEntity() == null || !e.getEntity().getUUID().equals(actor)) return;
        safely(() -> emit("BLOCK_PLACE_EVENT", currentObservation(), map("side", e.getLevel().isClientSide() ? "client" : "server",
                "pos", e.getPos().toShortString(), "placed", e.getPlacedBlock().toString(), "against", e.getPlacedAgainst().toString(),
                "canceled", e.isCanceled(), "scope", "event_observation_final_state_in_followup_samples")));
    }
    private static void entityJoined(EntityJoinLevelEvent e) {
        if (!active || !isGlue(e.getEntity())) return;
        safely(() -> emit("GLUE_ENTITY_JOIN_EVENT", currentObservation(), map("side", e.getLevel().isClientSide ? "client" : "server",
                "entity", entity(e.getEntity()), "canceled", e.isCanceled(), "scope", "session_observation_not_automatic_causal_attribution")));
    }
    private static void clientTick(ClientTickEvent.Post e) {
        if (!active) return;
        safely(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !mc.player.getUUID().equals(actor)) { limit("player unavailable or changed"); return; }
            sample(CLIENT_WATCHES, ++clientTick, "client");
        });
    }
    private static void serverTick(ServerTickEvent.Post e) {
        if (active) safely(() -> sample(SERVER_WATCHES, e.getServer().getTickCount(), "server"));
    }
    private static void sample(ConcurrentLinkedDeque<Watch> watches, int now, String side) {
        for (Watch w : watches) {
            int elapsed = now - w.start;
            if (elapsed > 80 || w.player.level() != w.level || w.player.isRemoved()) { watches.remove(w); continue; }
            Map<String, Object> world = world(w.level, w.pos);
            String signature = JSON.toJson(world);
            if (!signature.equals(w.signature)) {
                emit("WORLD_TRANSITION", w.id, map("side", side, "elapsedTicks", elapsed, "world", world,
                        "scope", "per_tick_observation_can_include_other_manual_actions_or_scheduled_ticks"));
                w.signature = signature;
            }
            if (SAMPLE_TICKS.contains(elapsed)) emit("TIMED_SNAPSHOT", w.id, map("side", side, "elapsedTicks", elapsed,
                    "observation", snapshot(w.player, w.pos)));
        }
    }
    private static void addWatch(ConcurrentLinkedDeque<Watch> list, Watch watch) {
        if (!active) return;
        if (list.size() >= MAX_WATCHES) {
            Watch removed = list.poll();
            emit("WATCH_TRUNCATED", removed == null ? 0 : removed.id, map("reason", "64 concurrent observation windows"));
        }
        list.add(watch);
    }

    private static Map<String, Object> client(Minecraft mc) {
        if (mc.player == null) return map("player", "absent");
        var camera = mc.gameRenderer.getMainCamera();
        return map("dimension", mc.player.level().dimension().location().toString(), "position", mc.player.position().toString(),
                "bodyYaw", mc.player.getYRot(), "bodyPitch", mc.player.getXRot(), "headYaw", mc.player.getYHeadRot(),
                "cameraType", mc.options.getCameraType().name(), "cameraPosition", camera.getPosition().toString(),
                "cameraYaw", camera.getYRot(), "cameraPitch", camera.getXRot(), "cameraLook", camera.getLookVector().toString(),
                "mode", mode(mc),
                "crosshairForced", UnlockedCameraClient.shouldForceCrosshair(), "heldAim", String.valueOf(Arrays.toString(UnlockedCameraClient.continuousAimAngles())),
                "hit", describeHit(mc.hitResult), "main", stack(mc.player.getMainHandItem()), "off", stack(mc.player.getOffhandItem()),
                "screen", mc.screen == null ? "none" : mc.screen.getClass().getName(), "using", mc.player.isUsingItem(),
                "selected", mc.player.getInventory().selected);
    }
    private static Map<String, Object> mode(Minecraft mc) {
        Map<String, Object> result = map("unlockedCameraActive", UnlockedCameraClient.isUnlockedCameraActive(),
                "modEnabled", ClientConfig.TOGGLE_MOD.get(), "shoulderEnabled", ClientConfig.ENABLE_SHOULDER_OFFSET.get(),
                "shoulderConfiguredAmount", ClientConfig.SHOULDER_OFFSET_AMOUNT.get(),
                "shoulderMaxZoom", ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get(),
                "freelookEnabled", ClientConfig.ENABLE_FREELOOK.get(), "freelookKeepDirection", ClientConfig.FREELOOK_KEEP_DIRECTION.get());
        try {
            for (var entry : MODE_FIELDS.entrySet()) result.put(entry.getKey(), entry.getValue().get(null));
            result.put("actualFreelookHeld", freelookPredicate.invoke(null, mc));
        } catch (ReflectiveOperationException e) { result.put("modeReadError", e.toString()); }
        return result;
    }
    private static Map<String, Object> snapshot(Player player, BlockPos pos) {
        List<Object> inventory = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) inventory.add(map("slot", i, "stack", stack(stack)));
        }
        return map("levelTime", player.level().getGameTime(), "playerTick", player.tickCount,
                "position", player.position().toString(), "bodyYaw", player.getYRot(), "bodyPitch", player.getXRot(),
                "headYaw", player.getYHeadRot(), "selected", player.getInventory().selected, "inventory", inventory,
                "menu", player.containerMenu.getClass().getName(), "using", player.isUsingItem(),
                "world", world(player.level(), pos));
    }
    private static Map<String, Object> world(Level level, BlockPos pos) {
        if (pos == null) return map("target", "none");
        List<Object> blocks = new ArrayList<>();
        List<BlockPos> positions = new ArrayList<>(); positions.add(pos);
        for (Direction d : Direction.values()) positions.add(pos.relative(d));
        for (BlockPos p : positions) {
            if (!level.hasChunkAt(p)) { blocks.add(map("pos", p.toShortString(), "state", "not_loaded")); continue; }
            var be = level.getBlockEntity(p);
            blocks.add(map("pos", p.toShortString(), "state", level.getBlockState(p).toString(),
                    "blockEntity", be == null ? "none" : be.getClass().getName()));
        }
        List<Object> glue = new ArrayList<>(); int count = 0;
        for (Entity e : level.getEntities((Entity) null, new AABB(pos).inflate(8), ManualOffsetRecorder::isGlue)) {
            count++; if (glue.size() < 32) glue.add(entity(e));
        }
        return map("target", pos.toShortString(), "dimension", level.dimension().location().toString(),
                "blocks", blocks, "glue", glue, "glueCount", count, "glueTruncated", count > 32);
    }
    private static Object entity(Entity e) { return map("id", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
            "uuid", e.getUUID().toString(), "position", e.position().toString(), "bounds", e.getBoundingBox().toString(), "removed", e.isRemoved()); }
    private static boolean isGlue(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().contains("glue"); }
    private static Object stack(ItemStack s) {
        String components = s.getComponents().toString();
        return map("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), "count", s.getCount(),
                "components", components.length() > 8000 ? components.substring(0, 8000) : components,
                "componentsTruncated", components.length() > 8000);
    }
    private static Object describeHit(HitResult hit) {
        if (hit == null) return "none";
        Map<String, Object> m = map("type", hit.getType().name(), "location", hit.getLocation().toString());
        if (hit instanceof BlockHitResult b) { m.put("pos", b.getBlockPos().toShortString()); m.put("face", b.getDirection().getName()); m.put("inside", b.isInside()); }
        if (hit instanceof EntityHitResult e) m.put("entity", entity(e.getEntity()));
        return m;
    }
    private static BlockPos currentTarget() { return Minecraft.getInstance().hitResult instanceof BlockHitResult b ? b.getBlockPos() : null; }
    private static long currentObservation() { ServerContext s = SERVER.get().peek(); return s == null ? peek(CALLS.get()) : s.observation; }
    private static long peek(ArrayDeque<Long> stack) { return stack.isEmpty() ? 0 : stack.peek(); }
    private static void pop(ArrayDeque<Long> stack, long id) { if (!stack.isEmpty() && stack.peek() == id) stack.pop(); else stack.remove(id); }
    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i + 1]); return m;
    }
    private static void safely(Runnable task) {
        try { task.run(); } catch (Throwable error) { emit("RECORDER_OBSERVATION_ERROR", 0, map("error", error.toString())); }
    }
    private static synchronized void emit(String type, long id, Map<String, Object> details) {
        if (!active || writer == null) return;
        try {
            if (records >= MAX_RECORDS || characters >= MAX_CHARACTERS) { limit("log size bound"); return; }
            String line = JSON.toJson(map("time", Instant.now().toString(), "nanoTime", System.nanoTime(), "thread", Thread.currentThread().getName(),
                    "type", type, "id", id, "details", details));
            writer.write(line); writer.newLine(); writer.flush(); records++; characters += line.length();
        } catch (Throwable error) { close(); notifyOutput("MANUAL_OFFSET_RECORDER IO_ERROR " + error); }
    }
    private static synchronized void limit(String reason) {
        if (!active) return;
        // Do not recurse through emit when the record/size cap itself was reached.
        try { writer.write(JSON.toJson(map("time", Instant.now().toString(), "type", "LIMIT_STOP", "reason", reason))); writer.newLine(); writer.flush(); }
        catch (Exception ignored) {}
        close(); notifyOutput("MANUAL_OFFSET_RECORDER LIMIT_STOP " + reason);
    }
    private static void notifyOutput(String message) { try { if (output != null) output.accept(message); } catch (Throwable ignored) {} }
    private static synchronized void close() {
        active = false;
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        writer = null; CLIENT_WATCHES.clear(); SERVER_WATCHES.clear(); PACKETS.clear(); PACKET_ORDER.clear();
    }
}
