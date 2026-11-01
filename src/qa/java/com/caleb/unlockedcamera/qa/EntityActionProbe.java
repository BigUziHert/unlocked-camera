package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Default server factories and real empty-hand entity requests. Not variants, trades, taming or abilities. */
public final class EntityActionProbe {
    private static final Path FILE = Path.of("qa", "entity-actions.tsv");
    private static final String ACK = "unlockedcamera_qa_entity_ack", TAG = "unlockedcamera_qa_entity_action";
    private static Vec3 PLAYER = new Vec3(1810.5, 180, 233.5);
    private static boolean running, registered, oldToggle, oldShoulder, oldPause;
    private static double oldMax;
    private static CameraType oldType;
    private static Map<Field, Object> fields;
    private static Method position, rotation, dropAim;
    private static List<EntityType<?>> types;
    private static Consumer<String> log;
    private static BufferedWriter writer;
    private static BufferedWriter freelookWriter;
    private static KeyMapping freelookKey;
    private static boolean oldFreelookKey;
    private static boolean oldFreelookEnabled, oldKeepDirection;
    private static boolean reviewOnly;
    private static boolean waitingForDrain;

    public static void runReview(Minecraft mc, Consumer<String> output) throws Exception {
        reviewOnly = true;
        PLAYER = new Vec3(1710.5, 180, 190.5);
        run(mc, line -> output.accept(line.replace("ENTITY_ACTIONS", "ENTITY_ACTIONS_RECHECK")));
    }
    private static int index, mode, phase, age, token, passed, differences, limited;
    private static volatile int targetId;
    private static volatile String setupIssue;
    private static volatile Entity target;
    private static volatile Snapshot snapshot;
    private static volatile Observation ack;
    private static Set<UUID> originals;
    private static boolean bodyAlsoHits;
    private static boolean oldShiftKey, oldInputShift;
    private static CompletableFuture<Void> task;
    private static Observation initial, first;
    private static String picked, use, firstPick, firstUse;
    private record Snapshot(ServerLevel level, Vec3 pos, float yaw, float pitch, GameType gameType,
                            List<ItemStack> inventory, int selected, boolean flying, float health,
                            List<MobEffectInstance> effects) {}
    private record Observation(int token, String menu, String vehicle, boolean alive, float health,
                               String effects, String dimension, boolean teleportReady) {
        String signature() { return menu + "|" + vehicle + "|alive=" + alive + "|health=" + health + "|" + effects + "|" + dimension; }
    }

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isPassenger())
            throw new IllegalStateException("Entity action probe requires idle integrated-server player");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Entity action probe only runs in the disposable QA copy");
        log = output;
        types = new ArrayList<>(); BuiltInRegistries.ENTITY_TYPE.forEach(types::add);
        types.sort(Comparator.comparing(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
        if (reviewOnly) {
            Set<String> candidates = new HashSet<>();
            for (Path path : List.of(FILE, Path.of("qa", "entity-freelook-actions.tsv"))) {
                if (!Files.exists(path)) continue;
                for (String line : Files.readAllLines(path)) {
                    String[] cells = line.split("\t", -1);
                    if (cells.length > 1 && Set.of("ERROR", "OBSERVATION_DIFF", "TARGETING_DIFFERENCE").contains(cells[1])) candidates.add(cells[0]);
                    // The original constant-centre fixture overlaps very wide
                    // entities with the player; collision pushes the player out
                    // of its teleport/readiness gate. Retry those invalid
                    // controls with a surface-based distance, preserving originals.
                    if (cells.length > 8 && cells[1].equals("NEEDS_FIXTURE")
                            && (cells[8].contains("timeout phase=1") || cells[2].equals("ENTITY"))) candidates.add(cells[0]);
                }
            }
            types.removeIf(t -> !candidates.contains(BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
        }
        fields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field f = field(name); fields.put(f, f.get(null));
        }
        oldType = mc.options.getCameraType(); oldToggle = ClientConfig.TOGGLE_MOD.get();
        oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldPause = mc.options.pauseOnLostFocus;
        oldShiftKey = mc.options.keyShift.isDown(); oldInputShift = mc.player.input.shiftKeyDown;
        freelookKey = (KeyMapping) field("FREELOOK_KEY").get(null); oldFreelookKey = freelookKey.isDown();
        oldFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get(); oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        dropAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim");
        position.setAccessible(true); rotation.setAccessible(true); dropAim.setAccessible(true);
        Files.createDirectories(FILE.getParent());
        Path offsetOutput = reviewOnly ? Path.of("qa", "entity-actions-recheck.tsv") : FILE;
        Path freeOutput = Path.of("qa", reviewOnly ? "entity-freelook-actions-recheck.tsv" : "entity-freelook-actions.tsv");
        if (Files.exists(offsetOutput)) Files.copy(offsetOutput, offsetOutput.resolveSibling("entity-actions-previous-" + System.currentTimeMillis() + ".tsv"));
        writer = Files.newBufferedWriter(offsetOutput);
        freelookWriter = Files.newBufferedWriter(freeOutput);
        row("entity", "status", "first_pick", "first_use", "first_server", "shoulder_pick", "shoulder_use", "shoulder_server", "limits");
        writeRow(freelookWriter, "entity", "status", "first_pick", "first_use", "first_server", "freelook_pick", "freelook_use", "freelook_server", "limits");
        index = mode = phase = age = passed = differences = limited = 0;
        target = null; snapshot = null; running = true;
        if (!registered) { NeoForge.EVENT_BUS.addListener(EntityActionProbe::tick); registered = true; }
        mc.options.pauseOnLostFocus = false; mc.setScreen(null);
        task = server(mc, p -> {
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    inventory, p.getInventory().selected, p.getAbilities().flying, p.getHealth(),
                    p.getActiveEffects().stream().map(MobEffectInstance::new).toList());
            originals = new HashSet<>();
            for (Entity entity : snapshot.level.getAllEntities()) if (entity != null) originals.add(entity.getUUID());
            mc.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(ACK)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer source = context.getSource().getPlayerOrException();
                        if (running && source.getUUID().equals(p.getUUID())) ack = observe(source, IntegerArgumentType.getInteger(context, "token"));
                        return 1;
                    })));
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities();
            p.teleportTo(snapshot.level, PLAYER.x, PLAYER.y, PLAYER.z, 0, 0);
        });
        log.accept("ENTITY_ACTIONS START registered=" + types.size() + " scope=default-empty-mainhand-first-person-vs-offset-and-real-first-person-freelook");
        if (reviewOnly) log.accept("ENTITY_ACTIONS FIXTURE review=nearest-front-surface2.1blocks;complete-snapshot-before-multipart-removal;original-rows-preserved;loaded_chunk_position=" + PLAYER);
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) { abort("disconnected"); return; }
        try {
            age++;
            mc.player.getInventory().selected = 0;
            mc.options.keyShift.setDown(false); mc.player.input.shiftKeyDown = false;
            mc.player.setShiftKeyDown(false);
            if (age > 1200) {
                boolean mutationPending = task != null && !task.isDone();
                boolean receiptPending = (phase == 2 || phase == 3) && (ack == null || ack.token != token);
                if (mutationPending || receiptPending) {
                    if (!waitingForDrain) log.accept("ENTITY_ACTIONS WAIT_DRAIN entity=" + id() + " phase=" + phase
                            + " mutationPending=" + mutationPending + " receiptPending=" + receiptPending
                            + ";no-next-case-or-cleanup-until-drained");
                    waitingForDrain = true;
                    return;
                }
                if (waitingForDrain) { age = 0; waitingForDrain = false; }
                else { recordLimit("NEEDS_FIXTURE", "timeout phase=" + phase); afterFailure(mc); return; }
            }
            if (phase == 0) {
                if (!task.isDone()) return;
                task.join(); if (age < 10) return;
                if (types.isEmpty()) { finish(mc); return; }
                prepare(mc); return;
            }
            if (phase == 1) {
                if (!task.isDone()) return;
                task.join();
                if (!setupIssue.isEmpty()) { recordLimit("NEEDS_FIXTURE", setupIssue); afterFailure(mc); return; }
                if (targetId < 0) { recordLimit("NOT_TARGETABLE_DEFAULT", "default entity excludes selection"); next(mc); return; }
                Entity entity = mc.level.getEntity(targetId);
                if (target != null && target.isRemoved()) { recordLimit("NEEDS_FIXTURE", "default entity self-removes before request"); afterFailure(mc); return; }
                if (entity == null || mc.player.position().distanceToSqr(PLAYER) > .04 || !mc.player.getMainHandItem().isEmpty()) return;
                if (age < 5) return;
                pose(mc, entity);
                ++token; ack = null; mc.player.connection.sendCommand(ACK + " " + token);
                phase = 3; age = 0; return;
            }
            if (phase == 3) {
                if (ack == null || ack.token != token) return;
                if (age < 3) return; // Allow the real freelook Pre-tick aim hold to become active.
                if (!ack.teleportReady) {
                    ++token; ack = null; mc.player.connection.sendCommand(ACK + " " + token); return;
                }
                Entity entity = mc.level.getEntity(targetId);
                if (entity == null) { recordLimit("NEEDS_FIXTURE", "entity removed before request"); afterFailure(mc); return; }
                perform(mc, entity); return;
            }
            if (phase == 2) {
                if (ack == null || ack.token != token) return;
                if (mode == 0) {
                    first = ack; firstPick = picked; firstUse = use; mode = 1; prepare(mc);
                } else {
                    String status = !firstPick.equals("TARGET") ? "NEEDS_FIXTURE"
                            : !picked.equals("TARGET") ? "TARGETING_DIFFERENCE"
                            : !first.signature().equals(ack.signature()) || !firstUse.equals(use) ? "OBSERVATION_DIFF"
                            : !ack.signature().equals(initial.signature()) ? bodyAlsoHits ? "OBSERVED_PARITY_PASS_BODY_OVERLAP" : "OBSERVED_PARITY_PASS" : "NO_OBSERVABLE_EFFECT";
                    writeRow(mode == 1 ? writer : freelookWriter, id(), status, firstPick, firstUse, first.signature(), picked, use, ack.signature(),
                            "view=" + (mode == 1 ? "offset" : "first_person_freelook") + ";default_factory;no_spawn_finalization;empty_mainhand;direct_camera_pose;body_ray_hits_target=" + bodyAlsoHits + ";no_trade_tame_variant_tool_or_custom_packet_validation");
                    if (status.startsWith("OBSERVED_PARITY_PASS")) passed++;
                    else if (status.endsWith("DIFFERENCE") || status.equals("OBSERVATION_DIFF")) { differences++; log.accept("ENTITY_ACTIONS " + status + " " + id()); }
                    else limited++;
                    if (mode == 1) { mode = 2; prepare(mc); } else next(mc);
                }
            }
        } catch (Throwable error) {
            try { recordLimit("ERROR", error.toString()); afterFailure(mc); }
            catch (Throwable fatal) { abort(fatal.toString()); }
        }
    }

    private static void prepare(Minecraft mc) {
        mc.setScreen(null); mc.player.stopRiding();
        freelookKey.setDown(false);
        try { dropAim.invoke(null); } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        targetId = -1; setupIssue = ""; ack = null;
        task = server(mc, p -> {
            p.stopRiding(); p.closeContainer(); p.stopUsingItem();
            cleanup(p); target = null;
            p.setShiftKeyDown(false);
            p.teleportTo(snapshot.level, PLAYER.x, PLAYER.y, PLAYER.z, 0, 0);
            p.getInventory().clearContent(); p.getInventory().selected = 0; p.removeAllEffects();
            p.getAbilities().flying = true; p.onUpdateAbilities(); p.containerMenu.broadcastChanges();
            try {
                Entity entity = types.get(index).create(snapshot.level);
                if (entity == null) { setupIssue = "NO_DEFAULT_FACTORY"; return; }
                if (!entity.isPickable()) { entity.discard(); return; }
                target = entity;
                entity.addTag(TAG); entity.setInvulnerable(true); entity.setNoGravity(true);
                if (entity instanceof Mob mob) mob.setNoAi(true);
                Vec3 eye = p.getEyePosition();
                double centreDistance = reviewOnly ? entity.getBbWidth() / 2.0 + 2.1 : 2.1;
                entity.moveTo(eye.x, eye.y - entity.getBbHeight() / 2.0, eye.z + centreDistance, 0, 0);
                entity.setDeltaMovement(Vec3.ZERO);
                if (!snapshot.level.addFreshEntity(entity)) { setupIssue = "SERVER_ADD_REJECTED"; return; }
                targetId = entity.getId(); initial = observe(p, -1);
            } catch (Throwable error) { setupIssue = error.toString(); }
        });
        phase = 1; age = 0; waitingForDrain = false;
    }

    private static void pose(Minecraft mc, Entity entity) throws Exception {
        boolean shoulder = mode == 1, free = mode == 2;
        ClientConfig.TOGGLE_MOD.set(mode != 0); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        field("active").set(null, shoulder); field("targetDistance").set(null, 4f);
        freelookKey.setDown(free);
        mc.options.setCameraType(shoulder ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        Vec3 eye = mc.player.getEyePosition(), origin = shoulder ? eye.add(.75, 0, -4) : eye;
        Vec3 direction = entity.getBoundingBox().getCenter().subtract(origin).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        Camera camera = mc.gameRenderer.getMainCamera();
        position.invoke(camera, origin); rotation.invoke(camera, yaw, pitch, 0f);
        // Deliberately separate body and crosshair, so a body-ray repick cannot
        // silently pass just because this fixture happens to be straight ahead.
        mc.player.setYRot(mode != 0 ? yaw + 75 : yaw); mc.player.yRotO = mc.player.getYRot();
        mc.player.setYHeadRot(mc.player.getYRot()); mc.player.yHeadRotO = mc.player.getYRot();
        mc.player.setXRot(mode != 0 ? 0 : pitch); mc.player.xRotO = mc.player.getXRot();
        field("freelookYaw").set(null, free ? yaw - mc.player.getYRot() : 0f);
        field("freelookPitch").set(null, free ? pitch - mc.player.getXRot() : 0f);
    }

    private static void perform(Minecraft mc, Entity entity) throws Exception {
        pose(mc, entity);
        Vec3 eye = mc.player.getEyePosition();
        var cameraVector = mc.gameRenderer.getMainCamera().getLookVector();
        Vec3 direction = new Vec3(cameraVector.x(), cameraVector.y(), cameraVector.z());
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), mc.player.getXRot(), false));
        double reach = mc.player.entityInteractionRange();
        Vec3 bodyDirection = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        EntityHitResult bodyHit = ProjectileUtil.getEntityHitResult(mc.player, eye, eye.add(bodyDirection.scale(reach)),
                mc.player.getBoundingBox().expandTowards(bodyDirection.scale(reach)).inflate(1),
                e -> !e.isSpectator() && e.isPickable(), reach * reach);
        bodyAlsoHits = bodyHit != null && bodyHit.getEntity() == entity;
        HitResult hit = mode != 0 ? UnlockedCameraClient.cameraRayPick(mc.player, mc.player.blockInteractionRange(), reach, 1)
                : ProjectileUtil.getEntityHitResult(mc.player, eye, eye.add(direction.scale(reach)),
                mc.player.getBoundingBox().expandTowards(direction.scale(reach)).inflate(1), e -> !e.isSpectator() && e.isPickable(), reach * reach);
        picked = hit instanceof EntityHitResult e && e.getEntity() == entity ? "TARGET" : hit == null ? "NULL" : hit.getType().toString();
        use = "NOT_SENT";
        if (picked.equals("TARGET")) {
            mc.hitResult = hit;
            InteractionResult result = mc.gameMode.interactAt(mc.player, entity, (EntityHitResult) hit, InteractionHand.MAIN_HAND);
            if (!result.consumesAction()) result = mc.gameMode.interact(mc.player, entity, InteractionHand.MAIN_HAND);
            use = result.toString();
        }
        ++token; ack = null; mc.player.connection.sendCommand(ACK + " " + token);
        phase = 2; age = 0;
    }

    private static Observation observe(ServerPlayer p, int token) {
        String effects = p.getActiveEffects().stream().map(e -> e.getEffect().getRegisteredName() + ":" + e.getAmplifier()).sorted().toList().toString();
        return new Observation(token, p.containerMenu == p.inventoryMenu ? "inventory" : p.containerMenu.getClass().getName(),
                p.getVehicle() == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString(),
                target != null && target.isAlive(), target instanceof LivingEntity living ? living.getHealth() : -1,
                effects, p.serverLevel().dimension().location().toString(), teleportReady(p));
    }

    private static boolean teleportReady(ServerPlayer player) {
        try {
            Field field = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            field.setAccessible(true); return field.get(player.connection) == null;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot verify teleport readiness", error); }
    }
    private static void cleanup(ServerPlayer player) {
        AABB area = new AABB(PLAYER.add(-24, -24, -24), PLAYER.add(24, 24, 24));
        List<Entity> remove = new ArrayList<>();
        for (Entity entity : snapshot.level.getAllEntities())
            if (entity != null && (entity.getTags().contains(TAG) || (!originals.contains(entity.getUUID()) && entity.getBoundingBox().intersects(area)))) remove.add(entity);
        // Multipart removal can invalidate/null entries in the live iterable.
        // Complete the snapshot before discarding anything from the world.
        for (Entity entity : remove) if (!entity.isRemoved()) entity.discard();
        if (target != null && !target.isRemoved()) target.discard();
    }

    private static void recordLimit(String status, String detail) throws Exception {
        if (mode <= 1) row(id(), status, "", "", "", "", "", "", detail);
        if (mode == 0 || mode == 2) writeRow(freelookWriter, id(), status, "", "", "", "", "", "", detail);
        if (status.equals("ERROR")) differences++; else limited++;
    }
    private static void afterFailure(Minecraft mc) throws Exception {
        if (mode == 1) { mode = 2; prepare(mc); } else next(mc);
    }
    private static void next(Minecraft mc) throws Exception {
        index++; mode = 0;
        if (index % 32 == 0) { writer.flush(); freelookWriter.flush(); log.accept("ENTITY_ACTIONS PROGRESS " + index + "/" + types.size() + " pass=" + passed + " differences=" + differences + " limited=" + limited); }
        if (index >= types.size()) finish(mc); else prepare(mc);
    }
    private static void finish(Minecraft mc) throws Exception {
        running = false; writer.close(); freelookWriter.close(); mc.setScreen(null);
        for (var entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax); mc.options.setCameraType(oldType); mc.options.pauseOnLostFocus = oldPause;
        mc.options.keyShift.setDown(oldShiftKey); mc.player.input.shiftKeyDown = oldInputShift;
        freelookKey.setDown(oldFreelookKey);
        ClientConfig.ENABLE_FREELOOK.set(oldFreelookEnabled); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        task = server(mc, p -> {
            p.stopRiding(); p.closeContainer();
            cleanup(p);
            p.getInventory().clearContent();
            for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.selected;
            p.setGameMode(snapshot.gameType); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
            p.removeAllEffects(); for (var effect : snapshot.effects) p.addEffect(new MobEffectInstance(effect)); p.setHealth(snapshot.health);
            p.teleportTo(snapshot.level, snapshot.pos.x, snapshot.pos.y, snapshot.pos.z, snapshot.yaw, snapshot.pitch);
            p.containerMenu.broadcastChanges();
            log.accept("ENTITY_ACTIONS RESTORED rows=" + index + " pass=" + passed + " differences=" + differences + " limited=" + limited);
        });
        log.accept("ENTITY_ACTIONS " + (index == types.size() ? "DONE" : "STOPPED") + " rows=" + index + " pass=" + passed + " differences=" + differences + " limited=" + limited);
    }
    private static void abort(String reason) {
        running = false;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (snapshot != null && mc.player != null && mc.getSingleplayerServer() != null) finish(mc);
            else if (writer != null) writer.close();
        } catch (Exception restoreError) { log.accept("ENTITY_ACTIONS RESTORE_ERROR " + restoreError); }
        log.accept("ENTITY_ACTIONS ABORT " + reason + "; disposable fixture may remain");
    }
    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> operation) {
        UUID id = mc.player.getUUID();
        return mc.getSingleplayerServer().submit(() -> operation.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id)))
                .whenComplete((ignored, error) -> { if (error != null) log.accept("ENTITY_ACTIONS SERVER_ERROR " + error); });
    }
    private static String id() { return BuiltInRegistries.ENTITY_TYPE.getKey(types.get(index)).toString(); }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void row(Object... cells) throws Exception {
        writeRow(writer, cells);
    }
    private static void writeRow(BufferedWriter target, Object... cells) throws Exception {
        for (int i = 0; i < cells.length; i++) { if (i > 0) target.write('\t'); target.write(String.valueOf(cells[i]).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')); }
        target.newLine();
    }
}
