package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every registered default stack, both hands, five view states. Exercises the
 * loaded camera and packet-rewrite hooks without sending the synthetic packets.
 * This is NOT a use/attack/render/gameplay certification: charged/data-component
 * variants, inventory ticks, server effects and mod-specific action packets need
 * their own integration fixtures. Only present in the opt-in QA build.
 */
public final class RegistryItemProbe {
    private static final String[] MODES = {
            "shoulder_left", "shoulder_right", "freelook_left", "freelook_right", "first_person"};
    private RegistryItemProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.isSameThread() || mc.player == null || mc.level == null || mc.getConnection() == null
                || mc.getSingleplayerServer() == null || mc.player.isUsingItem() || mc.player.isPassenger()) {
            throw new IllegalStateException("Registry item probe requires an idle, unmounted local singleplayer");
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) throw new IllegalStateException("Camera not initialized");
        Method position = method(Camera.class, "setPosition", Vec3.class);
        Method rotation = method(Camera.class, "setRotation", float.class, float.class, float.class);
        Method ranged = method(UnlockedCameraClient.class, "holdingRangedItem", Minecraft.class);
        Method ability = method(Class.forName("com.caleb.unlockedcamera.client.SimplySwordsCompat"),
                "isAbilityHeld", LocalPlayer.class);
        Method useHook = mergedMethod(mc.getConnection().getClass(), "unlockedcamera$aimUsePacket");
        Method moveHook = mergedMethod(mc.getConnection().getClass(), "unlockedcamera$holdAimRotation");
        BetterCombat combat = BetterCombat.find();
        Path directory = Path.of("qa", "registry-items");
        Files.createDirectories(directory);
        List<Item> items = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(items::add);
        items.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
        Map<Field, Object> saved = new LinkedHashMap<>();
        for (String name : new String[] {"active", "targetDistance", "freelookYaw", "freelookPitch",
                "cachedHeldAim", "trackedPlayer", "aimResyncRequested", "lastRecordedAim",
                "aimHistoryYaw", "aimHistoryPitch", "aimHistoryNanos", "aimHistoryNext", "aimHistoryCount"}) {
            Field f = field(UnlockedCameraClient.class, name);
            Object value = f.get(null);
            // History arrays are final and modified in place by the actual packet hook.
            saved.put(f, Modifier.isFinal(f.getModifiers()) && value instanceof float[] a ? a.clone()
                    : Modifier.isFinal(f.getModifiers()) && value instanceof long[] a ? a.clone() : value);
        }
        ItemStack originalMain = mc.player.getInventory().getSelected();
        ItemStack originalOff = mc.player.getInventory().offhand.get(0);
        Vec3 originalPosition = camera.getPosition();
        float originalYaw = camera.getYRot(), originalPitch = camera.getXRot(), originalRoll = camera.getRoll();
        float bodyYaw = mc.player.getYRot(), bodyPitch = mc.player.getXRot();
        CameraType originalType = mc.options.getCameraType();
        HitResult originalHit = mc.hitResult;
        boolean toggle = ClientConfig.TOGGLE_MOD.get(), shoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        double max = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        int cases = 0, failures = 0, suppressed = 0, combatChecks = 0, registeredEmpty = 0;
        log.accept("REGISTRY_ITEMS START registered=" + items.size()
                + " modes=5 hands=2 packets=transformed-hooks-no-network gameplay=NOT_EXECUTED");
        try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("camera-paths.csv"))) {
            row(out, "item", "hand", "mode", "visible_stack", "animation", "ranged_classifier",
                    "ability_classifier", "camera_packet_status", "bettercombat_attacks", "gameplay", "detail");
            ClientConfig.TOGGLE_MOD.set(true);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            set("trackedPlayer", mc.player);
            set("targetDistance", 4.0f);
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            mc.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            Map<String, HitResult> baselines = new LinkedHashMap<>();
            for (Item item : items) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                ItemStack defaultStack;
                try {
                    defaultStack = item.getDefaultInstance();
                } catch (Throwable failure) {
                    failures++;
                    row(out, id, "ALL", "ALL", "ERROR", "", "", "", "FAIL_STACK", 0,
                            "NOT_EXECUTED", rootMessage(failure));
                    continue;
                }
                if (defaultStack.isEmpty()) registeredEmpty++;
                for (InteractionHand hand : InteractionHand.values()) {
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    mc.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    mc.player.setItemInHand(hand, defaultStack.copy());
                    for (String mode : MODES) {
                        cases++;
                        String animation = "", visible = "", status = "PASS_CAMERA_PACKET";
                        boolean isRanged = false, isAbility = false;
                        int attacks = 0;
                        String detail = "synthetic packet hooks; no use, attack, render or server effect";
                        try {
                            pose(mc, mode, position, rotation);
                            ItemStack equipped = mc.player.getItemInHand(hand);
                            visible = BuiltInRegistries.ITEM.getKey(equipped.getItem()).toString();
                            animation = equipped.getUseAnimation().name();
                            boolean hidden = !defaultStack.isEmpty() && equipped.isEmpty();
                            if (hidden) {
                                suppressed++;
                                status = "PASS_CAMERA_PACKET_OFFHAND_SUPPRESSED";
                                detail += "; installed equipment getter suppresses this hand";
                            }
                            isRanged = (boolean) ranged.invoke(null, mc);
                            isAbility = (boolean) ability.invoke(null, mc.player);
                            // Better Combat and extended-reach items legitimately change the
                            // player's reported reach immediately when the held stack changes.
                            // Compare against empty hands using the SAME explicit ranges.
                            double blockRange = mc.player.blockInteractionRange();
                            double entityRange = mc.player.entityInteractionRange();
                            String baselineKey = mode + ":" + blockRange + ":" + entityRange;
                            if (!baselines.containsKey(baselineKey)) {
                                ItemStack main = mc.player.getInventory().getSelected();
                                ItemStack off = mc.player.getInventory().offhand.get(0);
                                try {
                                    mc.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                                    mc.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                                    baselines.put(baselineKey, UnlockedCameraClient.cameraRayPick(mc.player,
                                            blockRange, entityRange, 1f));
                                } finally {
                                    mc.player.setItemInHand(InteractionHand.MAIN_HAND, main);
                                    mc.player.setItemInHand(InteractionHand.OFF_HAND, off);
                                }
                            }
                            HitResult picked = UnlockedCameraClient.cameraRayPick(mc.player, blockRange, entityRange, 1f);
                            require(samePick(picked, baselines.get(baselineKey)), "held stack changed equal-reach ray pick");
                            boolean active = !mode.equals("first_person");
                            Vec3 forward = vector(camera);
                            Vec3 direction = UnlockedCameraClient.crosshairRayDirection(mc.player);
                            float[] aim = UnlockedCameraClient.crosshairAimAngles();
                            if (active) {
                                require(direction != null && direction.dot(forward) > .99999,
                                        "crosshair direction differs from camera");
                                require(aim != null && Float.isFinite(aim[0]) && Float.isFinite(aim[1])
                                        && Float.isFinite(aim[2]) && aim[1] >= -90 && aim[1] <= 90,
                                        "non-finite or missing corrected aim");
                                Vec3 gap = UnlockedCameraClient.crosshairRayGapFreeOrigin(mc.player);
                                require(gap != null && finite(gap), "invalid Create gap-free ray origin");
                                require(Math.abs(gap.subtract(mc.player.getEyePosition()).dot(forward)) < 1.0E-4,
                                        "Create ray begins at wrong player depth");
                            } else {
                                require(direction == null && aim == null && picked == null,
                                        "inactive view did not delegate");
                            }
                            // Exercise cache-backed movement and use rewrites for eligible held items,
                            // and the uncached click-time rewrite for ordinary items. No packet is sent.
                            boolean hold = active && (isRanged || isAbility || mode.startsWith("freelook"));
                            set("cachedHeldAim", hold ? aim : null);
                            set("aimResyncRequested", false);
                            ServerboundUseItemPacket use = new ServerboundUseItemPacket(hand, 123, 11, -7);
                            ServerboundUseItemPacket rewritten = (ServerboundUseItemPacket) useHook.invoke(mc.getConnection(), use);
                            require(rewritten.getHand() == hand && rewritten.getSequence() == 123,
                                    "use packet changed hand or sequence");
                            require(active ? close(rewritten.getYRot(), aim[0]) && close(rewritten.getXRot(), aim[1])
                                    : rewritten == use, "use packet aim rewrite/delegation");
                            require(field(UnlockedCameraClient.class, "aimResyncRequested").getBoolean(null)
                                    == (active && !hold), "uncached use resynchronization flag");
                            ServerboundMovePlayerPacket.Rot rot = new ServerboundMovePlayerPacket.Rot(11, -7, true);
                            ServerboundMovePlayerPacket moved = (ServerboundMovePlayerPacket) moveHook.invoke(mc.getConnection(), rot);
                            require(hold ? close(moved.getYRot(0), aim[0]) && close(moved.getXRot(0), aim[1])
                                    : moved == rot, "rotation packet cached aim/delegation");
                            ServerboundMovePlayerPacket.PosRot pos = new ServerboundMovePlayerPacket.PosRot(1, 2, 3, 11, -7, true);
                            ServerboundMovePlayerPacket movedPos = (ServerboundMovePlayerPacket) moveHook.invoke(mc.getConnection(), pos);
                            require(movedPos.getX(0) == 1 && movedPos.getY(0) == 2 && movedPos.getZ(0) == 3
                                    && movedPos.isOnGround(), "position packet coordinates or ground flag changed");
                            require(hold ? close(movedPos.getYRot(0), aim[0]) && close(movedPos.getXRot(0), aim[1])
                                    : movedPos == pos, "position packet cached aim/delegation");
                            if (combat != null && !hidden && !equipped.isEmpty()) {
                                attacks = combat.check(mc, equipped, active);
                                combatChecks += attacks;
                            }
                            require(mc.player.getYRot() == 0 && mc.player.getXRot() == 0,
                                    "test path changed the player's body rotation");
                        } catch (Throwable failure) {
                            failures++;
                            status = "FAIL_CAMERA_PACKET";
                            detail = rootMessage(failure);
                            log.accept("REGISTRY_ITEMS FAIL item=" + id + " hand=" + hand + " mode=" + mode + " " + detail);
                        }
                        row(out, id, hand, mode, visible, animation, isRanged, isAbility, status,
                                attacks, "NOT_EXECUTED", detail);
                    }
                }
                if (cases % 5000 == 0) {
                    out.flush();
                    log.accept("REGISTRY_ITEMS PROGRESS cases=" + cases + " failures=" + failures);
                }
            }
        } finally {
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, originalMain);
            mc.player.setItemInHand(InteractionHand.OFF_HAND, originalOff);
            mc.player.setYRot(bodyYaw);
            mc.player.setXRot(bodyPitch);
            mc.hitResult = originalHit;
            for (Map.Entry<Field, Object> entry : saved.entrySet()) {
                Field f = entry.getKey();
                Object value = entry.getValue();
                if (Modifier.isFinal(f.getModifiers()) && value instanceof float[] a) {
                    System.arraycopy(a, 0, f.get(null), 0, a.length);
                } else if (Modifier.isFinal(f.getModifiers()) && value instanceof long[] a) {
                    System.arraycopy(a, 0, f.get(null), 0, a.length);
                } else f.set(null, value);
            }
            ClientConfig.TOGGLE_MOD.set(toggle);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(shoulder);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(max);
            mc.options.setCameraType(originalType);
            rotation.invoke(camera, originalYaw, originalPitch, originalRoll);
            position.invoke(camera, originalPosition);
        }
        String summary = "REGISTRY_ITEMS DONE registered=" + items.size() + " cases=" + cases
                + " failures=" + failures + " suppressedHandCases=" + suppressed
                + " registeredEmptyStacks=" + registeredEmpty + " bettercombatAttackChecks=" + combatChecks
                + " gameplay=NOT_EXECUTED artifact=" + directory.resolve("camera-paths.csv");
        Files.writeString(directory.resolve("summary.txt"), summary + System.lineSeparator()
                + "Default stacks only. Equipped synchronously; inventory/equipment ticks, rendered models, charged/NBT/data-component variants, actual item use, attacks, damage, custom ability packets and server outcomes are NOT tested here."
                + System.lineSeparator());
        log.accept(summary);
    }

    private static void pose(Minecraft mc, String mode, Method position, Method rotation) throws Exception {
        boolean first = mode.equals("first_person");
        boolean free = mode.startsWith("freelook");
        float yaw = first ? 0 : mode.endsWith("left") ? -60 : 60;
        float pitch = first ? 0 : 12;
        set("active", !first && !free);
        set("freelookYaw", free ? yaw : 0f);
        set("freelookPitch", free ? pitch : 0f);
        mc.options.setCameraType(first || free ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        rotation.invoke(mc.gameRenderer.getMainCamera(), yaw, pitch, 0f);
        Vec3 eye = mc.player.getEyePosition();
        Vec3 forward = vector(mc.gameRenderer.getMainCamera());
        Vec3 right = new Vec3(forward.z, 0, -forward.x).normalize();
        position.invoke(mc.gameRenderer.getMainCamera(), first || free ? eye
                : eye.subtract(forward.scale(4)).add(right.scale(mode.endsWith("left") ? -.75 : .75)));
    }

    private static HitResult pick(Minecraft mc) {
        return UnlockedCameraClient.cameraRayPick(mc.player, mc.player.blockInteractionRange(),
                mc.player.entityInteractionRange(), 1f);
    }

    private static boolean samePick(HitResult a, HitResult b) {
        if (a == null || b == null) return a == b;
        return a.getType() == b.getType() && a.getLocation().distanceToSqr(b.getLocation()) < 1.0E-8
                && (!(a instanceof EntityHitResult x) || b instanceof EntityHitResult y && x.getEntity() == y.getEntity());
    }

    private static Vec3 vector(Camera camera) {
        var v = camera.getLookVector();
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static boolean close(float a, float b) { return Math.abs(a - b) < .0001f; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void set(String name, Object value) throws Exception { field(UnlockedCameraClient.class, name).set(null, value); }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method m = type.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static Method mergedMethod(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().contains(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Packet.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException("Loaded packet hook " + name);
    }

    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.toString().replace('\r', ' ').replace('\n', ' ');
    }

    private static void row(BufferedWriter writer, Object... cells) throws Exception {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) writer.write(',');
            writer.write('"');
            writer.write(String.valueOf(cells[i]).replace("\"", "\"\""));
            writer.write('"');
        }
        writer.newLine();
    }

    /** Uses every attack entry of every equipped Better Combat weapon, without attacking. */
    private static final class BetterCombat {
        private final Method attributes, find, origin;
        private BetterCombat(Method attributes, Method find, Method origin) {
            this.attributes = attributes;
            this.find = find;
            this.origin = origin;
        }
        static BetterCombat find() throws Exception {
            try {
                Class<?> registry = Class.forName("net.bettercombat.logic.WeaponRegistry");
                Class<?> finder = Class.forName("net.bettercombat.client.collision.TargetFinder");
                for (Method candidate : finder.getMethods()) {
                    if (candidate.getName().equals("findAttackTargetResult") && candidate.getParameterCount() == 4) {
                        return new BetterCombat(registry.getMethod("getAttributes", ItemStack.class), candidate,
                                finder.getMethod("getInitialTracingPoint", Player.class));
                    }
                }
                throw new NoSuchMethodException("Better Combat target finder");
            } catch (ClassNotFoundException absent) { return null; }
        }
        int check(Minecraft mc, ItemStack stack, boolean active) throws Exception {
            Object weapon = attributes.invoke(null, stack);
            if (weapon == null) return 0;
            Object[] attacks = (Object[]) weapon.getClass().getMethod("attacks").invoke(weapon);
            Vec3 from = (Vec3) origin.invoke(null, mc.player);
            Vec3 expected = active ? vector(mc.gameRenderer.getMainCamera()) : Vec3.directionFromRotation(0, 0);
            Entity target = EntityType.PIG.create(mc.level);
            if (target == null) throw new IllegalStateException("Cannot create synthetic Better Combat target");
            Vec3 center = from.add(expected.scale(2));
            target.setPos(center.x, center.y - target.getBbHeight() / 2, center.z);
            HitResult previousHit = mc.hitResult;
            try {
                mc.hitResult = new EntityHitResult(target, center);
                for (int index = 0; index < attacks.length; index++) {
                    Object result = find.invoke(null, mc.player, target, attacks[index], 4.5);
                    Object obb = result.getClass().getField("obb").get(result);
                    Vec3 actual = (Vec3) obb.getClass().getField("axisZ").get(obb);
                    require(actual.normalize().dot(expected.normalize()) > .99999,
                            "Better Combat attack " + index + " hitbox axis not camera-aligned");
                }
            } finally { mc.hitResult = previousHit; }
            return attacks.length;
        }
    }
}
