package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Cardboard swords deliberately cancel damage; checks their actual server knockback. */
public final class CardboardKnockbackProbe {
    private static final Vec3 PLAYER = new Vec3(1710.5, 150, 190.5);
    private static final Vec3 FORWARD = Vec3.directionFromRotation(0, 75);
    private static final String TAG = "unlockedcamera_qa_cardboard_knockback";
    private static final String[] MODES = {"first_person", "shoulder", "freelook"};
    private static boolean running, registered, oldToggle, oldShoulder, oldKey, rechecking;
    private static double oldAmount, oldMax;
    private static int mode, phase, age, knockedBack, directionReviews, readyAge;
    private static volatile int attackEvents;
    private static volatile boolean canceled, serverReady;
    private static volatile float serverYaw, health;
    private static volatile Vec3 impactVelocity, start, end;
    private static volatile Cow victim;
    private static volatile Snapshot snapshot;
    private static Consumer<String> log;
    private static CompletableFuture<Void> task;
    private static BufferedWriter writer;
    private static Map<Field, Object> fields;
    private static KeyMapping freelook;
    private static CameraType oldType;
    private static Method attack, position, rotation, dropAim;
    private record Snapshot(Vec3 position, float yaw, float pitch, GameType mode, int selected, List<ItemStack> inventory) {}

    private CardboardKnockbackProbe() {}

    public static void runRecheck(Minecraft mc, Consumer<String> output) throws Exception {
        rechecking = true;
        run(mc, line -> output.accept(line.replace("CARDBOARD_KNOCKBACK", "CARDBOARD_KNOCKBACK_RECHECK")));
    }

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running) throw new IllegalStateException("Cardboard probe already active");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || mc.player.isPassenger())
            throw new IllegalStateException("Cardboard probe requires idle local QA world");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Cardboard probe restricted to disposable QA copy");
        for (Class<?> controller : new Class<?>[] { IntegrationSceneProbe.class, ItemUseProbe.class, MeleeBaselineProbe.class }) {
            Field active = controller.getDeclaredField("running"); active.setAccessible(true);
            if (active.getBoolean(null)) throw new IllegalStateException("Wait for " + controller.getSimpleName());
        }
        log = output;
        fields = new LinkedHashMap<>();
        for (String name : new String[] {"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field f = field(name); fields.put(f, f.get(null));
        }
        freelook = (KeyMapping) field("FREELOOK_KEY").get(null);
        oldKey = freelook.isDown(); oldType = mc.options.getCameraType();
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        attack = Minecraft.class.getDeclaredMethod("startAttack"); attack.setAccessible(true);
        position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true);
        rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); rotation.setAccessible(true);
        dropAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); dropAim.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(CardboardKnockbackProbe::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, CardboardKnockbackProbe::attackObserved);
            registered = true;
        }
        Files.createDirectories(Path.of("qa"));
        Path evidence = Path.of("qa", rechecking ? "cardboard-knockback-recheck.tsv" : "cardboard-knockback.tsv");
        if (Files.exists(evidence)) Files.copy(evidence, evidence.resolveSibling(evidence.getFileName() + ".previous-" + System.currentTimeMillis()));
        writer = Files.newBufferedWriter(evidence);
        writer.write("mode\tstatus\tattack_events\tcanceled_damage\thealth\tdisplacement\timpact_velocity\tserver_body_yaw\ttarget_direction_error_degrees\n");
        snapshot = null; mode = phase = age = knockedBack = directionReviews = 0; running = true;
        log.accept("CARDBOARD_KNOCKBACK START modes=first_person,shoulder,freelook damageIsIntentionallyCanceled=true");
        task = server(mc, p -> {
            if (p.serverLevel().getBlockState(BlockPos.containing(PLAYER).below()).isAir())
                throw new IllegalStateException("Existing IntegrationSceneProbe platform missing");
            List<ItemStack> inventory = new ArrayList<>();
            for (int n = 0; n < p.getInventory().getContainerSize(); n++) inventory.add(p.getInventory().getItem(n).copy());
            snapshot = new Snapshot(p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(), p.getInventory().selected, inventory);
            p.setGameMode(GameType.CREATIVE); p.stopUsingItem(); p.closeContainer(); p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
        });
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null)
                throw new IllegalStateException("QA world closed");
            if (++age > 1200) {
                readinessDiagnostics(mc, "TIMEOUT");
                throw new IllegalStateException("Timeout mode=" + MODES[mode] + " phase=" + phase);
            }
            if (phase == 0) {
                if (!task.isDone()) return;
                task.join();
                if (age < 20 || mc.player.position().distanceToSqr(PLAYER) > .02) return;
                phase = 1; age = 0;
            }
            pose(mc);
            switch (phase) {
                case 1 -> {
                    dropAim.invoke(null);
                    readyAge = -1;
                    serverReady = false;
                    mc.gameMode.releaseUsingItem(mc.player);
                    task = server(mc, p -> {
                        cleanup(p); p.stopUsingItem(); p.closeContainer(); p.getInventory().clearContent(); p.getInventory().selected = 0;
                        p.setItemInHand(InteractionHand.MAIN_HAND,
                                BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:cardboard_sword")).getDefaultInstance());
                        p.setYRot(mode == 2 ? 0 : 75); p.setXRot(0); p.setSprinting(false);
                        p.containerMenu.broadcastFullState();
                        Cow cow = EntityType.COW.create(p.serverLevel());
                        if (cow == null) throw new IllegalStateException("Cow factory unavailable");
                        start = PLAYER.add(FORWARD.scale(2)).add(0, .35, 0);
                        cow.setPos(start); cow.setNoAi(false); cow.setNoGravity(true); cow.setPersistenceRequired(); cow.addTag(TAG);
                        // NoAI also disables LivingEntity.travel, hiding real
                        // knockback. Remove behavior while retaining physics.
                        cow.goalSelector.removeAllGoals(goal -> true);
                        cow.targetSelector.removeAllGoals(goal -> true);
                        cow.getNavigation().stop();
                        cow.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
                        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); cow.setHealth(100);
                        p.serverLevel().addFreshEntity(cow); victim = cow;
                        impactVelocity = Vec3.ZERO; attackEvents = 0; canceled = false; health = Float.NaN;
                    });
                    phase = 2; age = 0;
                }
                case 2 -> {
                    if (!task.isDone()) return;
                    task.join();
                    // The fixture initialized server body yaw. Let the actual pre-tick hold
                    // establish its own server aim AFTER that initialization, then retain
                    // the cache: clearing it every Post tick defeats movement rewriting.
                    if (readyAge < 0) {
                        dropAim.invoke(null); readyAge = age;
                        readinessDiagnostics(mc, "READY_GATE_INITIAL"); return;
                    }
                    if (!serverReady) {
                        task = server(mc, p -> serverReady = teleportReady(p)
                                && BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString().equals("create:cardboard_sword"));
                        return;
                    }
                    if (!BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals("create:cardboard_sword")) return;
                    Entity target = victim == null ? null : mc.level.getEntity(victim.getId());
                    if (age - readyAge < 5 || target == null || mc.player.getAttackStrengthScale(0) < .99999f || mc.player.isUsingItem()) return;
                    float[] heldAim = UnlockedCameraClient.continuousAimAngles();
                    if (mode == 2 && heldAim == null) return;
                    mc.gameRenderer.pick(1);
                    if (!(mc.hitResult instanceof EntityHitResult hit) || hit.getEntity() != target)
                        throw new IllegalStateException("Crosshair fixture missed cow: " + mc.hitResult);
                    Object result = attack.invoke(mc);
                    log.accept("CARDBOARD_KNOCKBACK REQUEST mode=" + MODES[mode] + " cooldown=1.0 startAttack=" + result
                            + " clientBodyYaw=" + mc.player.getYRot() + " cameraYaw=" + mc.gameRenderer.getMainCamera().getYRot()
                            + " heldAim=" + java.util.Arrays.toString(heldAim));
                    phase = 3; age = 0;
                }
                case 3 -> {
                    if (age < 40) return;
                    task = server(mc, p -> { end = victim.position(); health = victim.getHealth(); });
                    phase = 4; age = 0;
                }
                case 4 -> {
                    if (!task.isDone()) return;
                    task.join();
                    double distance = end.distanceTo(start);
                    Vec3 horizontal = impactVelocity.multiply(1, 0, 1);
                    double error = horizontal.lengthSqr() < 1e-8 ? Double.NaN
                            : Math.toDegrees(Math.acos(Math.clamp(horizontal.normalize().dot(FORWARD), -1, 1)));
                    boolean moved = attackEvents > 0 && canceled && health == 100 && distance > .1 && horizontal.lengthSqr() > .001;
                    boolean directional = moved && error > 15;
                    if (moved) knockedBack++;
                    if (directional) directionReviews++;
                    String status = !moved ? "FAIL_KNOCKBACK" : directional ? "PASS_KNOCKBACK_DIRECTION_REVIEW" : "PASS_KNOCKBACK";
                    writer.write(MODES[mode] + "\t" + status + "\t" + attackEvents + "\t" + canceled + "\t" + health
                            + "\t" + distance + "\t" + impactVelocity + "\t" + serverYaw + "\t" + error + "\n"); writer.flush();
                    log.accept("CARDBOARD_KNOCKBACK mode=" + MODES[mode] + " " + status + " health=" + health
                            + " displacement=" + distance + " velocity=" + impactVelocity + " serverYaw=" + serverYaw + " directionError=" + error);
                    mode++; phase = 1; age = 0;
                    if (mode == MODES.length) finish(mc, "DONE");
                }
                default -> throw new IllegalStateException("Unexpected phase=" + phase);
            }
        } catch (Throwable failure) {
            while (failure.getCause() != null) failure = failure.getCause();
            log.accept("CARDBOARD_KNOCKBACK ERROR " + failure);
            try { finish(mc, "ABORT"); } catch (Throwable restore) { log.accept("CARDBOARD_KNOCKBACK RESTORE_ERROR " + restore); }
        }
    }

    private static void attackObserved(AttackEntityEvent event) {
        if (!running || event.getEntity().level().isClientSide || event.getTarget() != victim) return;
        attackEvents++; canceled = event.isCanceled(); impactVelocity = victim.getDeltaMovement(); serverYaw = event.getEntity().getYRot();
    }

    private static void readinessDiagnostics(Minecraft mc, String reason) {
        Cow current = victim;
        log.accept("CARDBOARD_KNOCKBACK " + reason + " mode=" + MODES[mode] + " phase=" + phase
                + " age=" + age + " readyAge=" + readyAge + " taskDone=" + (task != null && task.isDone())
                + " serverReady=" + serverReady
                + " clientHeld=" + BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem())
                + " selected=" + mc.player.getInventory().selected + " using=" + mc.player.isUsingItem()
                + " cooldown=" + mc.player.getAttackStrengthScale(0) + " clientPosition=" + mc.player.position()
                + " targetId=" + (current == null ? "none" : current.getId())
                + " clientTargetPresent=" + (current != null && mc.level.getEntity(current.getId()) != null));
        server(mc, p -> log.accept("CARDBOARD_KNOCKBACK " + reason + "_SERVER held="
                + BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()) + " selected=" + p.getInventory().selected
                + " using=" + p.isUsingItem() + " bodyYaw=" + p.getYRot() + " position=" + p.position()
                + " teleportReady=" + teleportReady(p)
                + " targetRemoved=" + (current == null || current.isRemoved())));
    }

    private static boolean teleportReady(ServerPlayer player) {
        try {
            Field pending = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            pending.setAccessible(true); return pending.get(player.connection) == null;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot verify teleport readiness", error); }
    }

    private static void pose(Minecraft mc) throws Exception {
        float bodyYaw = mode == 2 ? 0 : 75;
        ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        field("active").setBoolean(null, mode == 1); field("targetDistance").setFloat(null, 4);
        field("freelookYaw").setFloat(null, mode == 2 ? 75 : 0); field("freelookPitch").setFloat(null, 0);
        freelook.setDown(mode == 2); mc.options.setCameraType(mode == 1 ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        mc.player.setYRot(bodyYaw); mc.player.yRotO = bodyYaw; mc.player.setXRot(0); mc.player.xRotO = 0;
        mc.player.setSprinting(false); mc.player.getInventory().selected = 0; mc.setScreen(null);
        Vec3 eye = mc.player.getEyePosition();
        Vec3 camera = mode == 1 ? eye.subtract(FORWARD.scale(4)).add(Math.cos(Math.toRadians(75)) * .95, 0, Math.sin(Math.toRadians(75)) * .95) : eye;
        Vec3 ray = eye.add(FORWARD.scale(2)).subtract(camera).normalize();
        float cameraYaw = (float) Math.toDegrees(Math.atan2(-ray.x, ray.z));
        float cameraPitch = (float) -Math.toDegrees(Math.atan2(ray.y, Math.sqrt(ray.x * ray.x + ray.z * ray.z)));
        position.invoke(mc.gameRenderer.getMainCamera(), camera); rotation.invoke(mc.gameRenderer.getMainCamera(), cameraYaw, cameraPitch, 0f);
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object custom = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (custom != null) {
            Class<?> type = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
            Object turn = type.getConstructor(float.class, float.class).newInstance(bodyYaw, 0f);
            custom.getClass().getMethod("setCameraRotation", type).invoke(custom, turn);
            Field playerRotation = custom.getClass().getDeclaredField("playerRotation"); playerRotation.setAccessible(true); playerRotation.set(custom, turn);
        }
    }

    private static void cleanup(ServerPlayer p) {
        for (Entity e : p.serverLevel().getAllEntities()) if (e.getTags().contains(TAG)) e.discard();
        if (victim != null && !victim.isRemoved()) victim.discard();
        victim = null;
    }

    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var id = mc.player.getUUID();
        return mc.getSingleplayerServer().submit(() -> action.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id)));
    }

    private static void finish(Minecraft mc, String outcome) throws Exception {
        running = false;
        if (snapshot != null) server(mc, p -> {
            cleanup(p); p.getInventory().clearContent();
            for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode);
            p.teleportTo(snapshot.position.x, snapshot.position.y, snapshot.position.z); p.setYRot(snapshot.yaw); p.setXRot(snapshot.pitch);
            p.containerMenu.broadcastChanges(); log.accept("CARDBOARD_KNOCKBACK RESTORED");
        });
        for (var entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        freelook.setDown(oldKey); ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax); mc.options.setCameraType(oldType);
        if (writer != null) writer.close();
        log.accept("CARDBOARD_KNOCKBACK " + outcome + " modes=" + MODES.length + " knockedBack=" + knockedBack + " directionReviews=" + directionReviews);
    }

    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
}
