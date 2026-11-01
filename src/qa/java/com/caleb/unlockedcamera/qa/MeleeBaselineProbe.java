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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Failed-only real first-person/free-look attacks, gated on full actual cooldown. */
public final class MeleeBaselineProbe {
    private static final Vec3 PLAYER = new Vec3(1710.5, 150, 190.5);
    private static final AABB AREA = new AABB(1703, 149, 183, 1718, 158, 205);
    private static final String TAG = "unlockedcamera_qa_melee_recheck";
    private static Consumer<String> log;
    private static List<String> items;
    private static boolean running, registered, oldToggle, oldKey;
    private static int index, mode, phase, age, observationDelay, passed, failed;
    private static float baselineHealth, strength;
    private static String detail;
    private static volatile float observedHealth;
    private static volatile Cow victim;
    private static volatile Snapshot snapshot;
    private static CompletableFuture<Void> task;
    private static BufferedWriter writer;
    private static Map<Field, Object> fields;
    private static CameraType oldType;
    private static KeyMapping freelook;
    private static Method attack, position, rotation, dropAim;
    private static boolean trace;
    private static volatile int attackEvents;
    private record Snapshot(Vec3 position, float yaw, float pitch, GameType mode, int selected, List<ItemStack> inventory) {}

    private MeleeBaselineProbe() {}

    /** Loads dynamically; run after the current IntegrationSceneProbe has completed. */
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        run(mc, output, false);
    }

    public static void runRemaining(Minecraft mc, Consumer<String> output) throws Exception {
        run(mc, output, true);
    }

    private static void run(Minecraft mc, Consumer<String> output, boolean remaining) throws Exception {
        if (running) throw new IllegalStateException("Melee recheck already active");
        Field integrationRunning = IntegrationSceneProbe.class.getDeclaredField("running");
        integrationRunning.setAccessible(true);
        if (integrationRunning.getBoolean(null)) throw new IllegalStateException("Wait for the main melee sweep to finish");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null)
            throw new IllegalStateException("Melee recheck requires the isolated integrated-server world");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Melee recheck restricted to disposable QA copy");
        log = output;
        List<String> lines = Files.readAllLines(Path.of("qa", "results.log"));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("INTEGRATION START suite=melee ")) start = i;
        if (start < 0) throw new IllegalStateException("No preceding all-melee suite found");
        var ids = new LinkedHashSet<String>();
        var pattern = Pattern.compile("^MELEE ([^ ]+) FAIL .*remainingHealth=.*");
        for (int i = start; i < lines.size(); i++) {
            var match = pattern.matcher(lines.get(i));
            if (match.matches()) ids.add(match.group(1));
        }
        if (remaining) {
            var passedPattern = Pattern.compile("^MELEE_RECHECK ([^ ]+) PASS .*");
            for (int i = start; i < lines.size(); i++) {
                var match = passedPattern.matcher(lines.get(i));
                if (match.matches()) ids.remove(match.group(1));
            }
        }
        items = List.copyOf(ids);
        trace = remaining;
        if (items.isEmpty()) { log.accept("MELEE_RECHECK DONE failedItems=0"); return; }
        writer = Files.newBufferedWriter(Path.of("qa", "melee-recheck.tsv"));
        writer.write("item\tmode\tstatus\tcooldown\tremaining_health\tdiagnostics\n");
        fields = new LinkedHashMap<>();
        for (String name : new String[] {"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field f = field(name); fields.put(f, f.get(null));
        }
        freelook = (KeyMapping) field("FREELOOK_KEY").get(null);
        oldKey = freelook.isDown(); oldType = mc.options.getCameraType(); oldToggle = ClientConfig.TOGGLE_MOD.get();
        attack = Minecraft.class.getDeclaredMethod("startAttack"); attack.setAccessible(true);
        position = Camera.class.getDeclaredMethod("setPosition", Vec3.class); position.setAccessible(true);
        rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); rotation.setAccessible(true);
        dropAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim"); dropAim.setAccessible(true);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(MeleeBaselineProbe::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, MeleeBaselineProbe::attackObserved);
            registered = true;
        }
        index = mode = phase = age = passed = failed = 0; running = true;
        log.accept("MELEE_RECHECK START failedItems=" + items.size() + " ids=" + items + " fullCooldownRequired=true");
        task = server(mc, p -> {
            if (p.serverLevel().getBlockState(BlockPos.containing(PLAYER).below()).isAir())
                throw new IllegalStateException("Existing IntegrationSceneProbe platform missing");
            List<ItemStack> inventory = new ArrayList<>();
            for (int n = 0; n < p.getInventory().getContainerSize(); n++) inventory.add(p.getInventory().getItem(n).copy());
            snapshot = new Snapshot(p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getInventory().selected, inventory);
            p.setGameMode(GameType.CREATIVE); p.stopRiding(); p.closeContainer();
            p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
        });
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null)
                throw new IllegalStateException("QA world closed");
            if (++age > 600) throw new IllegalStateException("Recheck timeout phase=" + phase + " item=" + items.get(index));
            if (phase == 0) {
                if (!task.isDone()) return;
                task.join();
                if (mc.player.position().distanceToSqr(PLAYER) > .02 || age < 20) return;
                phase = 1; age = 0;
            }
            pose(mc);
            switch (phase) {
                case 1 -> {
                    String id = items.get(index);
                    task = server(mc, p -> {
                        cleanup(p);
                        p.getInventory().clearContent(); p.getInventory().selected = 0;
                        p.setItemInHand(InteractionHand.MAIN_HAND,
                                BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance());
                        p.setYRot(mode == 0 ? 75 : 0); p.setXRot(0);
                        p.containerMenu.broadcastChanges();
                        Cow cow = EntityType.COW.create(p.serverLevel());
                        if (cow == null) throw new IllegalStateException("Cow factory unavailable");
                        Vec3 point = PLAYER.add(Vec3.directionFromRotation(0, 75).scale(2));
                        cow.setPos(point.x, PLAYER.y + .35, point.z);
                        cow.setNoAi(true); cow.setNoGravity(true); cow.setPersistenceRequired(); cow.addTag(TAG);
                        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); cow.setHealth(100);
                        p.serverLevel().addFreshEntity(cow); victim = cow;
                        attackEvents = 0;
                    });
                    phase = 2; age = 0;
                }
                case 2 -> {
                    if (!task.isDone()) return;
                    task.join();
                    ItemStack expected = expectedDefault(items.get(index));
                    if (mc.player.getMainHandItem().getItem() != expected.getItem()) return;
                    Entity target = victim == null ? null : mc.level.getEntity(victim.getId());
                    if (target == null || age < 3 || mc.player.getAttackStrengthScale(0) < .99999f || mc.player.isUsingItem()) return;
                    mc.gameRenderer.pick(1);
                    if (!(mc.hitResult instanceof EntityHitResult hit) || hit.getEntity() != target)
                        throw new IllegalStateException("Recheck crosshair fixture misses target: " + mc.hitResult);
                    strength = mc.player.getAttackStrengthScale(0);
                    detail = "actualHeld=" + BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem())
                            + ";" + targetDiagnostics(mc, target);
                    Object returned = attack.invoke(mc);
                    detail += ";startAttack=" + returned + ";upswingAfter=" + invokeOptional(mc, "getUpswingTicks");
                    log.accept("MELEE_BASELINE REQUEST item=" + items.get(index) + " mode=" + modeName()
                            + " cooldown=" + strength + " " + detail);
                    observationDelay = Math.max(40, Math.min(200, (int) Math.ceil(mc.player.getCurrentItemAttackStrengthDelay()) + 8));
                    phase = 3; age = 0;
                }
                case 3 -> {
                    if (trace && age <= 18) traceSwing(mc);
                    if (age < observationDelay) return;
                    task = server(mc, p -> observedHealth = victim == null ? Float.NaN : victim.getHealth());
                    phase = 4; age = 0;
                }
                case 4 -> {
                    if (!task.isDone()) return;
                    task.join();
                    boolean damage = Float.isFinite(observedHealth) && observedHealth < 100;
                    String status = damage ? "PASS_DAMAGE" : "NO_DAMAGE";
                    writer.write(items.get(index) + "\t" + modeName() + "\t" + status + "\t" + strength
                            + "\t" + observedHealth + "\t" + detail.replace('\t', ' ').replace('\n', ' ') + "\n");
                    writer.flush();
                    log.accept("MELEE_BASELINE item=" + items.get(index) + " mode=" + modeName()
                            + " " + status + " remainingHealth=" + observedHealth + " cooldown=" + strength
                            + " serverAttackEvents=" + attackEvents);
                    if (mode == 0) { baselineHealth = observedHealth; mode = 1; phase = 1; age = 0; }
                    else {
                        boolean baselinePass = Float.isFinite(baselineHealth) && baselineHealth < 100;
                        String outcome = baselinePass && damage ? "PASS" : baselinePass ? "CAMERA_FAIL_REVIEW"
                                : damage ? "BASELINE_ONLY_FAILURE_REVIEW" : "BASELINE_FAIL";
                        if (baselinePass && damage) passed++; else failed++;
                        log.accept("MELEE_RECHECK " + items.get(index) + " " + outcome + " firstHealth=" + baselineHealth
                                + " freelookHealth=" + observedHealth + " fullCooldownRequired=true");
                        index++; mode = 0; phase = 1; age = 0;
                        if (index == items.size()) finish(mc, "DONE");
                    }
                }
                default -> throw new IllegalStateException("Invalid recheck phase " + phase);
            }
        } catch (Throwable failure) {
            while (failure.getCause() != null) failure = failure.getCause();
            log.accept("MELEE_RECHECK ERROR " + failure);
            try { finish(mc, "ABORT"); } catch (Throwable restore) { log.accept("MELEE_RECHECK RESTORE_ERROR " + restore); }
        }
    }

    private static ItemStack expectedDefault(String id) throws Exception {
        ItemStack stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();
        try {
            return (ItemStack) Class.forName("net.sweenus.simplyswords.util.LegacyUniqueMigration")
                    .getMethod("convertStack", ItemStack.class).invoke(null, stack);
        } catch (ClassNotFoundException absent) { return stack; }
    }

    private static void traceSwing(Minecraft mc) throws Exception {
        Field capturedField = Minecraft.class.getDeclaredField("upswingStack"); capturedField.setAccessible(true);
        ItemStack captured = (ItemStack) capturedField.get(mc);
        ItemStack current = mc.player.getMainHandItem();
        Entity target = victim == null ? null : mc.level.getEntity(victim.getId());
        log.accept("MELEE_TRACE item=" + items.get(index) + " mode=" + modeName() + " tick=" + age
                + " upswing=" + invokeOptional(mc, "getUpswingTicks") + " strength=" + mc.player.getAttackStrengthScale(0)
                + " item=" + BuiltInRegistries.ITEM.getKey(current.getItem()) + " components=" + current.getComponentsPatch().hashCode()
                + " captured=" + (captured == null ? "null" : captured.getComponentsPatch().hashCode())
                + " stackMatches=" + (captured != null && ItemStack.matches(current, captured))
                + " camera=" + mc.gameRenderer.getMainCamera().getYRot() + "," + mc.gameRenderer.getMainCamera().getXRot()
                + " body=" + mc.player.getYRot() + " serverAttackEvents=" + attackEvents
                + " " + (target == null ? "targetAbsent" : targetDiagnostics(mc, target)));
    }

    private static void attackObserved(AttackEntityEvent event) {
        if (!running || event.getEntity().level().isClientSide || victim == null || event.getTarget() != victim) return;
        attackEvents++;
        log.accept("MELEE_SERVER_ATTACK item=" + items.get(index) + " mode=" + modeName()
                + " canceled=" + event.isCanceled() + " yaw=" + event.getEntity().getYRot()
                + " strength=" + event.getEntity().getAttackStrengthScale(0) + " healthBefore=" + victim.getHealth());
    }

    private static void pose(Minecraft mc) throws Exception {
        float yaw = mode == 0 ? 75 : 0;
        ClientConfig.TOGGLE_MOD.set(true); field("active").setBoolean(null, false);
        field("freelookYaw").setFloat(null, mode == 0 ? 0 : 75); field("freelookPitch").setFloat(null, 0);
        freelook.setDown(mode != 0); mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setXRot(0); mc.player.xRotO = 0;
        mc.player.getInventory().selected = 0; mc.setScreen(null);
        if (mode == 0) dropAim.invoke(null);
        position.invoke(mc.gameRenderer.getMainCamera(), mc.player.getEyePosition());
        rotation.invoke(mc.gameRenderer.getMainCamera(), 75f, 0f, 0f);
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera != null) {
            Class<?> type = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
            Object turn = type.getConstructor(float.class, float.class).newInstance(yaw, 0f);
            camera.getClass().getMethod("setCameraRotation", type).invoke(camera, turn);
            Field p = camera.getClass().getDeclaredField("playerRotation"); p.setAccessible(true); p.set(camera, turn);
        }
    }

    private static String targetDiagnostics(Minecraft mc, Entity target) throws Exception {
        Method current = Minecraft.class.getDeclaredMethod("getCurrentHand"); current.setAccessible(true);
        Object hand = current.invoke(mc);
        if (hand == null) return "betterCombatHand=none";
        Object selectedAttack = hand.getClass().getMethod("attack").invoke(hand);
        double upswing = ((Number) hand.getClass().getMethod("upswingRate").invoke(hand)).doubleValue();
        double range = ((Number) Class.forName("net.bettercombat.logic.PlayerAttackHelper")
                .getMethod("getRangeForItem", Player.class, ItemStack.class).invoke(null, mc.player, mc.player.getMainHandItem())).doubleValue();
        range *= ((Number) selectedAttack.getClass().getMethod("rangeMultiplier").invoke(selectedAttack)).doubleValue();
        Class<?> finder = Class.forName("net.bettercombat.client.collision.TargetFinder");
        Object result = finder.getMethod("findAttackTargetResult", Player.class, Entity.class, selectedAttack.getClass(), double.class)
                .invoke(null, mc.player, target, selectedAttack, range);
        List<?> targets = (List<?>) result.getClass().getField("entities").get(result);
        Object box = result.getClass().getField("obb").get(result);
        Vec3 axis = (Vec3) box.getClass().getField("axisZ").get(box);
        return "targetSelected=" + targets.contains(target) + ";targetCount=" + targets.size() + ";axis=" + axis
                + ";attackAngle=" + selectedAttack.getClass().getMethod("angle").invoke(selectedAttack)
                + ";range=" + range + ";cooldownThreshold=" + (1 - upswing);
    }

    private static Object invokeOptional(Minecraft mc, String method) {
        try { return Minecraft.class.getMethod(method).invoke(mc); } catch (Exception e) { return e.toString(); }
    }
    private static CompletableFuture<Void> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var id = mc.player.getUUID();
        return mc.getSingleplayerServer().submit(() -> action.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id)));
    }
    private static void cleanup(ServerPlayer p) {
        for (Entity e : p.serverLevel().getAllEntities()) if (e.getTags().contains(TAG)) e.discard();
    }
    private static void finish(Minecraft mc, String result) throws Exception {
        running = false;
        if (snapshot != null) server(mc, p -> {
            cleanup(p); p.getInventory().clearContent();
            for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode);
            p.teleportTo(snapshot.position.x, snapshot.position.y, snapshot.position.z);
            p.setYRot(snapshot.yaw); p.setXRot(snapshot.pitch); p.containerMenu.broadcastChanges();
            log.accept("MELEE_RECHECK RESTORED");
        });
        for (var entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        freelook.setDown(oldKey); ClientConfig.TOGGLE_MOD.set(oldToggle); mc.options.setCameraType(oldType);
        if (writer != null) writer.close();
        log.accept("MELEE_RECHECK " + result + " entries=" + items.size() + " passed=" + passed + " failed=" + failed);
    }
    private static String modeName() { return mode == 0 ? "first_person" : "freelook"; }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
}
