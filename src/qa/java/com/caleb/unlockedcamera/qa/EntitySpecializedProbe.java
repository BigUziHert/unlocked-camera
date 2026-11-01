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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Supported mount plus bounded giant/multipart request delivery, in all three real views. */
public final class EntitySpecializedProbe {
    private static final Vec3 PLAYER = new Vec3(1710.5, 200, 190.5);
    private static final AABB AREA = new AABB(1704, 198, 182, 1718, 215, 205);
    private static final String ACK = "unlockedcamera_qa_entity_specialized_ack";
    private static final String[] IDS = {"alexsmobs:straddleboard", "minecraft:giant", "alexsmobs:bone_serpent"};
    private static final String[] MODES = {"first_person", "offset", "freelook"};
    private static final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    private static final Map<Field, Object> fields = new LinkedHashMap<>();
    private static final Map<String, Boolean> baselines = new LinkedHashMap<>();
    private static Consumer<String> log;
    private static CompletableFuture<?> task;
    private static boolean registered, running, restoring, fixtureReady, oldToggle, oldShoulder, oldFreelookEnabled, oldKeepDirection;
    private static boolean oldFreelookKey, oldShift, oldPause, bodyOverlap, cameraMatchesReference, targetFamily;
    private static double oldAmount, oldMax;
    private static CameraType oldType;
    private static KeyMapping freelookKey;
    private static Method cameraPosition, cameraRotation;
    private static Snapshot snapshot;
    private static Set<UUID> originals;
    private static volatile Entity root;
    private static volatile UUID selectedUuid;
    private static volatile Observation ack;
    private static volatile int serverEvents;
    private static volatile boolean armed;
    private static volatile String eventTargets;
    private static int caseIndex, phase, age, token, checks, failures, limited;
    private static String clientResult, targetDescription, referenceDescription;
    private static EntityHitResult selectedHit;
    private static Observation before, immediate;
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode,
                            boolean flying, float health, List<MobEffectInstance> effects, int slot, List<ItemStack> inventory) {}
    private record Observation(int token, long tick, boolean teleportReady, boolean strictReach, boolean eyeOutside,
                               boolean family, Vec3 targetPosition, Vec3 velocity, UUID vehicle, int events, String eventTargets) {}
    private EntitySpecializedProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isPassenger())
            throw new IllegalStateException("Idle integrated-server player required");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(java.nio.file.Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Only the disposable QA copy may run this fixture");
        log = output; caseIndex = phase = age = checks = failures = limited = 0;
        restoring = fixtureReady = armed = false; snapshot = null; root = null; selectedUuid = null;
        blocks.clear(); fields.clear(); baselines.clear();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "freelookYaw", "freelookPitch", "shoulderSide"}) {
            Field f = field(UnlockedCameraClient.class, name); fields.put(f, f.get(null));
        }
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get(); oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        freelookKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        oldFreelookKey = freelookKey.isDown(); oldShift = mc.options.keyShift.isDown();
        oldType = mc.options.getCameraType(); oldPause = mc.options.pauseOnLostFocus;
        cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        cameraPosition.setAccessible(true); cameraRotation.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(EntitySpecializedProbe::tick);
            NeoForge.EVENT_BUS.addListener(EntitySpecializedProbe::specific);
            NeoForge.EVENT_BUS.addListener(EntitySpecializedProbe::general);
            registered = true;
        }
        running = true; mc.options.pauseOnLostFocus = false; mc.setScreen(null);
        log.accept("ENTITY_SPECIALIZED START cases=9 views=first_person,offset,real_first_person_freelook keepDirection=false scope=board-mount-and-giant-multipart-request-delivery");
        task = server(mc, p -> {
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getAbilities().flying, p.getHealth(), p.getActiveEffects().stream().map(MobEffectInstance::new).toList(),
                    p.getInventory().selected, inventory);
            originals = new HashSet<>();
            for (Entity entity : snapshot.level.getAllEntities()) if (entity != null) originals.add(entity.getUUID());
            if (!snapshot.level.getEntities(p, AREA).isEmpty()) throw new IllegalStateException("Existing entities in specialized fixture");
            for (BlockPos pos : BlockPos.betweenClosed(1704, 199, 182, 1717, 214, 204)) {
                if (snapshot.level.getBlockEntity(pos) != null) throw new IllegalStateException("Existing block entity at " + pos);
                blocks.put(pos.immutable(), snapshot.level.getBlockState(pos));
            }
            fixtureReady = true;
            for (BlockPos pos : blocks.keySet()) snapshot.level.setBlock(pos,
                    pos.getY() == 199 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            mc.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(ACK)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer sender = context.getSource().getPlayerOrException();
                        if (running && sender.getUUID().equals(p.getUUID())) ack = observe(sender, IntegerArgumentType.getInteger(context, "token"));
                        return 1;
                    })));
        });
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 1200) throw new IllegalStateException("Specialized fixture timeout phase=" + phase);
            if (task != null) { if (!task.isDone()) return; task.join(); task = null; }
            if (restoring) {
                if (snapshot != null) { mc.player.getInventory().selected = snapshot.slot; body(mc, snapshot.yaw, snapshot.pitch); }
                running = false; log.accept("ENTITY_SPECIALIZED RESTORED checks=" + checks + " failures=" + failures + " limited=" + limited); return;
            }
            mc.player.getInventory().selected = 0;
            mc.options.keyShift.setDown(false); mc.player.setShiftKeyDown(false); mc.player.input.shiftKeyDown = false;
            if (phase == 0) { prepare(mc); return; }
            if (phase == 1) {
                if (age < 40) return; // Let the real support collision and multipart chain settle.
                Entity entity = root == null ? null : mc.level.getEntity(root.getId());
                if (entity == null || mc.player.position().distanceToSqr(PLAYER) > .04) return;
                pose(mc, entity);
                select(mc, entity);
                if (!targetFamily || !cameraMatchesReference) { completeCase(mc, false, "camera_selection"); return; }
                requestAck(mc); phase = 2; age = 0; return;
            }
            if (phase == 2) {
                Entity entity = mc.level.getEntity(root.getId());
                if (entity == null) throw new IllegalStateException("Specialized target disappeared");
                pose(mc, entity);
                if (ack == null || ack.token != token || age < 5) return;
                if (!ack.teleportReady) { requestAck(mc); age = 0; return; }
                before = ack;
                Entity selected = selectedHit.getEntity();
                double clientServerError = selected.position().distanceTo(ack.targetPosition);
                // Straddleboard retains about -0.05 Y velocity even while its real
                // floor collision keeps it still; noGravity does not govern it.
                boolean stable = ack.velocity.lengthSqr() < .004 && clientServerError < .12;
                log.accept("ENTITY_SPECIALIZED PREFLIGHT " + label() + " reach=" + ack.strictReach + " eyeOutside=" + ack.eyeOutside
                        + " family=" + ack.family + " stable=" + stable + " clientServerError=" + clientServerError
                        + " serverVelocity=" + ack.velocity + " target=" + targetDescription + " reference=" + referenceDescription);
                if (!before.strictReach || !before.eyeOutside || !before.family || !stable) {
                    limited++; log.accept("ENTITY_SPECIALIZED INCONCLUSIVE " + label() + " invalid-server-fixture"); next(mc); return;
                }
                // Re-pick at request time; if a real part moved to a different ray position, preflight it again.
                UUID checked = selectedUuid; select(mc, entity);
                if (!targetFamily || !cameraMatchesReference) { completeCase(mc, false, "camera_selection_at_request"); return; }
                if (!Objects.equals(checked, selectedUuid)) { requestAck(mc); age = 0; return; }
                serverEvents = 0; eventTargets = ""; armed = true;
                mc.hitResult = selectedHit;
                InteractionResult result = mc.gameMode.interactAt(mc.player, selectedHit.getEntity(), selectedHit, InteractionHand.MAIN_HAND);
                if (!result.consumesAction()) result = mc.gameMode.interact(mc.player, selectedHit.getEntity(), InteractionHand.MAIN_HAND);
                clientResult = result.toString(); requestAck(mc); phase = 3; age = 0; return;
            }
            if (phase == 3) {
                if (ack == null || ack.token != token) return;
                immediate = ack; requestAck(mc); phase = 4; age = 0; return;
            }
            if (phase == 4) {
                if (ack == null || ack.token != token) return;
                if (ack.tick < immediate.tick + 5) { if (age >= 5) { requestAck(mc); age = 0; } return; }
                boolean mount = !id().equals("alexsmobs:straddleboard")
                        || (root.getUUID().equals(immediate.vehicle) && root.getUUID().equals(ack.vehicle));
                boolean pass = immediate.events > 0 && ack.events > 0 && mount;
                completeCase(mc, pass, "serverEvents=" + immediate.events + "/" + ack.events + " eventTargets=" + ack.eventTargets
                        + " immediateVehicle=" + immediate.vehicle + " after5TicksVehicle=" + ack.vehicle
                        + " elapsedServerTicks=" + (ack.tick - immediate.tick)
                        + " scope=" + (id().equals("alexsmobs:straddleboard") ? "mounted-same-board" : "request-delivery-only"));
            }
        } catch (Throwable error) {
            log.accept("ENTITY_SPECIALIZED ERROR " + error);
            try { restore(mc, "ABORT"); } catch (Throwable fatal) { running = false; log.accept("ENTITY_SPECIALIZED RESTORE_ERROR " + fatal); }
        }
    }

    private static void prepare(Minecraft mc) throws Exception {
        armed = false; selectedUuid = null; selectedHit = null; before = immediate = ack = null;
        clientResult = "NOT_SENT"; targetDescription = referenceDescription = "none";
        mc.setScreen(null); mc.player.stopRiding(); freelookKey.setDown(false);
        field(UnlockedCameraClient.class, "active").setBoolean(null, false);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, 0);
        Method drop = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); drop.setAccessible(true); drop.invoke(null);
        task = server(mc, p -> {
            p.stopRiding(); p.closeContainer(); cleanup();
            p.setGameMode(GameType.CREATIVE); p.getInventory().clearContent(); p.getInventory().selected = 0;
            p.setShiftKeyDown(false); p.getAbilities().flying = false; p.onUpdateAbilities();
            p.teleportTo(snapshot.level, PLAYER.x, PLAYER.y, PLAYER.z, 0, 0); p.containerMenu.broadcastChanges();
            Entity entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id())).create(snapshot.level);
            if (entity == null) throw new IllegalStateException("No entity factory " + id());
            entity.setInvulnerable(true); entity.setNoGravity(true);
            if (entity instanceof Mob mob) mob.setNoAi(true);
            // The nearest box surface is two blocks ahead, regardless of a giant's width.
            entity.moveTo(PLAYER.x, PLAYER.y, PLAYER.z + entity.getBbWidth() / 2.0 + 2.0, 0, 0);
            entity.setDeltaMovement(Vec3.ZERO); root = entity;
            if (!snapshot.level.addFreshEntity(entity)) throw new IllegalStateException("Entity add failed " + id());
        });
        phase = 1; age = 0;
    }

    private static void pose(Minecraft mc, Entity entity) throws Exception {
        int mode = caseIndex % 3; boolean shoulder = mode == 1, free = mode == 2;
        ClientConfig.TOGGLE_MOD.set(mode != 0); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        field(UnlockedCameraClient.class, "active").setBoolean(null, shoulder);
        field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4);
        field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
        field(UnlockedCameraClient.class, "shoulderSide").setInt(null, 1);
        mc.options.setCameraType(shoulder ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        Vec3 eye = mc.player.getEyePosition(); Vec3 origin = shoulder ? eye.add(.95, 0, -4) : eye;
        AABB box = entity.getBoundingBox();
        Vec3 point = new Vec3((box.minX + box.maxX) / 2, Math.max(box.minY + .1, Math.min(eye.y, box.maxY - .1)), (box.minZ + box.maxZ) / 2);
        Vec3 direction = point.subtract(origin).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        float bodyYaw = mode == 0 ? yaw : yaw + 90, bodyPitch = mode == 0 ? pitch : 0;
        body(mc, bodyYaw, bodyPitch); freelookKey.setDown(free);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, free ? yaw - bodyYaw : 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, free ? pitch - bodyPitch : 0);
        cameraPosition.invoke(mc.gameRenderer.getMainCamera(), origin); cameraRotation.invoke(mc.gameRenderer.getMainCamera(), yaw, pitch, 0f);
    }

    private static void select(Minecraft mc, Entity entity) {
        Camera camera = mc.gameRenderer.getMainCamera(); Vec3 origin = camera.getPosition(), eye = mc.player.getEyePosition();
        var look = camera.getLookVector(); Vec3 direction = new Vec3(look.x(), look.y(), look.z());
        double reach = mc.player.entityInteractionRange(), setback = origin.distanceTo(eye);
        Vec3 start = origin.add(direction.scale(Math.max(0, eye.subtract(origin).dot(direction))));
        Vec3 end = origin.add(direction.scale(reach + setback));
        EntityHitResult reference = ProjectileUtil.getEntityHitResult(mc.player, start, end, new AABB(start, end).inflate(1),
                e -> !e.isSpectator() && e.isPickable(), Math.pow(reach + setback, 2));
        HitResult actual = caseIndex % 3 == 0 ? reference
                : UnlockedCameraClient.cameraRayPick(mc.player, mc.player.blockInteractionRange(), reach, 1);
        selectedHit = actual instanceof EntityHitResult hit ? hit : null;
        targetDescription = describe(selectedHit); referenceDescription = describe(reference);
        selectedUuid = selectedHit == null ? null : selectedHit.getEntity().getUUID();
        // Parts expose parent UUIDs client-side but getParent() is server-only. Resolve their chain by UUID.
        targetFamily = selectedHit != null && clientFamily(mc, selectedHit.getEntity(), entity.getUUID());
        cameraMatchesReference = reference != null && selectedHit != null
                && selectedHit.getEntity().getUUID().equals(reference.getEntity().getUUID());
        Vec3 bodyDirection = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        EntityHitResult bodyHit = ProjectileUtil.getEntityHitResult(mc.player, eye, eye.add(bodyDirection.scale(reach)),
                mc.player.getBoundingBox().expandTowards(bodyDirection.scale(reach)).inflate(1), e -> !e.isSpectator() && e.isPickable(), reach * reach);
        bodyOverlap = bodyHit != null && clientFamily(mc, bodyHit.getEntity(), entity.getUUID());
        log.accept("ENTITY_SPECIALIZED GEOMETRY " + label() + " eye=" + eye + " rootBox=" + entity.getBoundingBox()
                + " eyeOutsideRoot=" + !entity.getBoundingBox().contains(eye)
                + " eyeToRootSurface=" + Math.sqrt(entity.getBoundingBox().distanceToSqr(eye))
                + " entityReach=" + reach + " actualType=" + (actual == null ? "null" : actual.getType())
                + " actualEyeDistance=" + (actual == null ? -1 : actual.getLocation().distanceTo(eye))
                + " referenceEyeDistance=" + (reference == null ? -1 : reference.getLocation().distanceTo(eye))
                + " bodyRayHitsFamily=" + bodyOverlap);
    }

    private static boolean clientFamily(Minecraft mc, Entity entity, UUID expected) {
        for (int i = 0; i < 24 && entity != null; i++) {
            if (entity.getUUID().equals(expected)) return true;
            if (!entity.getClass().getName().endsWith("EntityBoneSerpentPart")) return false;
            try {
                UUID parent = (UUID) entity.getClass().getMethod("getParentId").invoke(entity);
                if (expected.equals(parent)) return true;
                entity = null;
                for (Entity candidate : mc.level.entitiesForRendering()) if (candidate.getUUID().equals(parent)) { entity = candidate; break; }
            } catch (ReflectiveOperationException error) { return false; }
        }
        return false;
    }
    private static boolean serverFamily(Entity entity) {
        for (int i = 0; i < 24 && entity != null; i++) {
            if (root != null && entity.getUUID().equals(root.getUUID())) return true;
            if (!entity.getClass().getName().endsWith("EntityBoneSerpentPart")) return false;
            try { entity = (Entity) entity.getClass().getMethod("getParent").invoke(entity); }
            catch (ReflectiveOperationException error) { return false; }
        }
        return false;
    }
    private static void specific(PlayerInteractEvent.EntityInteractSpecific event) { recordEvent(event.getEntity(), event.getTarget(), "specific"); }
    private static void general(PlayerInteractEvent.EntityInteract event) { recordEvent(event.getEntity(), event.getTarget(), "general"); }
    private static void recordEvent(net.minecraft.world.entity.player.Player player, Entity entity, String kind) {
        if (!running || !armed || !(player instanceof ServerPlayer) || !Objects.equals(entity.getUUID(), selectedUuid) || !serverFamily(entity)) return;
        serverEvents++; eventTargets += kind + "@" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + ";";
    }
    private static Observation observe(ServerPlayer player, int token) {
        Entity entity = selectedUuid == null ? null : snapshot.level.getEntity(selectedUuid);
        boolean teleportReady;
        try { teleportReady = field(player.connection.getClass(), "awaitingPositionFromClient").get(player.connection) == null; }
        catch (Exception error) { throw new IllegalStateException(error); }
        return new Observation(token, snapshot.level.getGameTime(), teleportReady,
                entity != null && player.canInteractWithEntity(entity, 0), entity != null && !entity.getBoundingBox().contains(player.getEyePosition()),
                serverFamily(entity), entity == null ? Vec3.ZERO : entity.position(), entity == null ? Vec3.ZERO : entity.getDeltaMovement(),
                player.getVehicle() == null ? null : player.getVehicle().getUUID(), serverEvents, eventTargets);
    }
    private static void requestAck(Minecraft mc) { ++token; ack = null; mc.player.connection.sendCommand(ACK + " " + token); }
    private static void completeCase(Minecraft mc, boolean pass, String detail) throws Exception {
        boolean baseline = caseIndex % 3 == 0;
        if (baseline) baselines.put(id(), pass);
        boolean positive = baselines.getOrDefault(id(), false);
        String status = !baseline && (!positive || bodyOverlap) ? "INCONCLUSIVE" : pass ? "PASS" : "FAIL";
        if (status.equals("INCONCLUSIVE")) limited++; else { checks++; if (!pass) failures++; }
        log.accept("ENTITY_SPECIALIZED " + status + " " + label() + " positiveControl=" + positive + " clientResult=" + clientResult
                + " bodyRayHitsFamily=" + bodyOverlap + " cameraMatchesReference=" + cameraMatchesReference
                + " target=" + targetDescription + " reference=" + referenceDescription + " " + detail);
        next(mc);
    }
    private static void next(Minecraft mc) throws Exception { armed = false; if (++caseIndex == IDS.length * MODES.length) restore(mc, "DONE"); else prepare(mc); }
    private static void cleanup() {
        List<Entity> remove = new ArrayList<>();
        for (Entity entity : snapshot.level.getAllEntities())
            if (entity != null && !originals.contains(entity.getUUID())
                    && (serverFamily(entity) || entity.getBoundingBox().intersects(AREA))) remove.add(entity);
        for (Entity entity : remove) if (!entity.isRemoved()) entity.discard();
        if (root != null && !root.isRemoved()) root.discard(); root = null;
    }
    private static void restore(Minecraft mc, String status) throws Exception {
        armed = false; restoring = true; age = 0;
        for (var entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        ClientConfig.ENABLE_FREELOOK.set(oldFreelookEnabled); ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        freelookKey.setDown(oldFreelookKey); mc.options.keyShift.setDown(oldShift); mc.player.setShiftKeyDown(oldShift); mc.player.input.shiftKeyDown = oldShift;
        mc.options.pauseOnLostFocus = oldPause; mc.options.setCameraType(oldType);
        task = server(mc, p -> {
            if (snapshot == null) return;
            p.stopRiding(); p.closeContainer(); cleanup();
            if (fixtureReady) for (var entry : blocks.entrySet()) snapshot.level.setBlock(entry.getKey(), entry.getValue(), 3);
            p.getInventory().clearContent(); for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.slot; p.setGameMode(snapshot.mode); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
            p.removeAllEffects(); for (var effect : snapshot.effects) p.addEffect(new MobEffectInstance(effect)); p.setHealth(snapshot.health);
            p.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch); p.containerMenu.broadcastChanges();
        });
        log.accept("ENTITY_SPECIALIZED " + status + " checks=" + checks + " failures=" + failures + " limited=" + limited + " restoration=pending");
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
    private static String id() { return IDS[caseIndex / 3]; }
    private static String label() { return id() + " " + MODES[caseIndex % 3]; }
    private static String describe(EntityHitResult hit) { return hit == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(hit.getEntity().getType()) + "/" + hit.getEntity().getUUID(); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static CompletableFuture<?> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID(); return server.submit(() -> action.accept(server.getPlayerList().getPlayer(uuid)));
    }
}
