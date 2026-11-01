package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Actual Create client selection handlers; not schematic export or worldshaper block edits. */
public final class CreateSelectionProbe {
    private static final Vec3 PLAYER = new Vec3(1860.5, 220, 190.5);
    private static final BlockPos TARGET = new BlockPos(1862, 221, 193), BODY_TARGET = new BlockPos(1860, 221, 193);
    private static final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    private static final Map<Field, Object> cameraFields = new LinkedHashMap<>(), quillFields = new LinkedHashMap<>();
    private static Consumer<String> log;
    private static CompletableFuture<?> task;
    private static boolean registered, running, restoring, fixtureReady, focusedChecks;
    private static int age, checks, failures;
    private static Snapshot snapshot;
    private static Object quill, activate;
    private static Field activateModifiable, activateKeybind;
    private static boolean oldModifiable, oldUse, oldShift, oldToggle, oldShoulder, oldFreelook, oldFreelookEnabled, oldKeepDirection;
    private static KeyMapping freelookKey;
    private static Object oldKeybind;
    private static double oldMax;
    private static CameraType oldType;
    private static Vec3 oldCameraPosition;
    private static float oldCameraYaw, oldCameraPitch, oldCameraRoll;
    private static Method cameraPosition, cameraRotation;
    private record Snapshot(ServerLevel level, Vec3 position, float yaw, float pitch, GameType mode,
                            boolean flying, int selected, List<ItemStack> inventory) {}
    private CreateSelectionProbe() {}

    public static void runFocused(Minecraft mc, Consumer<String> output) throws Exception {
        run(mc, line -> output.accept(line.replace("CREATE_SELECTION", "CREATE_SELECTION_FIX")));
        focusedChecks = true;
    }

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isPassenger()
                || mc.player.isDeadOrDying() || mc.player.isRemoved())
            throw new IllegalStateException("Idle integrated-server player required");
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(java.nio.file.Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Only the disposable QA copy may run this fixture");
        log = output; age = checks = failures = 0; snapshot = null; restoring = fixtureReady = focusedChecks = false;
        blocks.clear(); cameraFields.clear(); quillFields.clear();
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(UnlockedCameraClient.class, name); cameraFields.put(field, field.get(null));
        }
        quill = Class.forName("com.simibubi.create.CreateClient").getField("SCHEMATIC_AND_QUILL_HANDLER").get(null);
        for (Field field : quill.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); quillFields.put(field, field.get(quill));
        }
        activate = Class.forName("com.simibubi.create.AllKeys").getField("ACTIVATE_TOOL").get(null);
        activateModifiable = field(activate.getClass(), "modifiable"); activateKeybind = field(activate.getClass(), "keybind");
        oldModifiable = activateModifiable.getBoolean(activate); oldKeybind = activateKeybind.get(activate);
        oldUse = mc.options.keyUse.isDown(); oldShift = mc.options.keyShift.isDown();
        freelookKey = (KeyMapping) field(UnlockedCameraClient.class, "FREELOOK_KEY").get(null);
        oldFreelook = freelookKey.isDown(); oldFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get();
        oldKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        oldToggle = ClientConfig.TOGGLE_MOD.get(); oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get(); oldType = mc.options.getCameraType();
        Camera camera = mc.gameRenderer.getMainCamera(); oldCameraPosition = camera.getPosition();
        oldCameraYaw = camera.getYRot(); oldCameraPitch = camera.getXRot(); oldCameraRoll = camera.getRoll();
        cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        cameraPosition.setAccessible(true); cameraRotation.setAccessible(true);
        if (!registered) { NeoForge.EVENT_BUS.addListener(CreateSelectionProbe::tick); registered = true; }
        running = true;
        log.accept("CREATE_SELECTION START scope=client-handler-selection-only; freelookKey=held-in-freelook; keepDirection=false; quill-input=temporary-predicate-routing-not-native-GLFW; no-export-or-worldshaper-edit");
        task = server(mc, p -> {
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            snapshot = new Snapshot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    p.getAbilities().flying, p.getInventory().selected, inventory);
            for (BlockPos pos : BlockPos.betweenClosed(1858, 219, 185, 1865, 224, 196)) {
                if (p.serverLevel().getBlockEntity(pos) != null) throw new IllegalStateException("Existing block entity in QA fixture: " + pos);
                blocks.put(pos.immutable(), p.serverLevel().getBlockState(pos));
            }
            if (!p.serverLevel().getEntities(p, new AABB(1858, 219, 185, 1866, 225, 197)).isEmpty())
                throw new IllegalStateException("Existing entity in QA fixture");
            fixtureReady = true;
            for (BlockPos pos : blocks.keySet()) p.serverLevel().setBlock(pos, pos.getY() == 219 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            p.serverLevel().setBlock(TARGET, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            p.serverLevel().setBlock(BODY_TARGET, Blocks.STONE.defaultBlockState(), 3);
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities();
            p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
            p.getInventory().selected = 0;
            p.setItemInHand(InteractionHand.MAIN_HAND, item("create:schematic_and_quill"));
            p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY); p.containerMenu.broadcastChanges();
            p.teleportTo(snapshot.level, PLAYER.x, PLAYER.y, PLAYER.z, 0, 0);
        });
    }

    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (task != null) { if (!task.isDone()) return; task.join(); task = null; }
            if (restoring) {
                running = false;
                if (snapshot != null) { mc.player.getInventory().selected = snapshot.selected; body(mc, snapshot.yaw, snapshot.pitch); }
                log.accept("CREATE_SELECTION RESTORED checks=" + checks + " failures=" + failures); return;
            }
            if (++age < 25) return;
            if (mc.player.position().distanceToSqr(PLAYER) > .01 || !mc.level.getBlockState(TARGET).is(Blocks.GOLD_BLOCK)) {
                if (age < 150) return;
                throw new IllegalStateException("Fixture client state did not synchronize pos=" + mc.player.position()
                        + " velocity=" + mc.player.getDeltaMovement() + " health=" + mc.player.getHealth()
                        + " dead=" + mc.player.isDeadOrDying() + " removed=" + mc.player.isRemoved()
                        + " screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                        + " gold=" + mc.level.getBlockState(TARGET));
            }
            mc.setScreen(null); mc.player.getInventory().selected = 0;
            mc.options.keyShift.setDown(false); mc.player.setShiftKeyDown(false); mc.player.input.shiftKeyDown = false;
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field(UnlockedCameraClient.class, "targetDistance").setFloat(null, 4);
            field(UnlockedCameraClient.class, "smoothedDistance").setFloat(null, 4);
            field(quill.getClass(), "outlineSlot").set(quill, new Object());
            field(quill.getClass(), "firstPos").set(quill, null); field(quill.getClass(), "secondPos").set(quill, null);
            field(quill.getClass(), "selectedFace").set(quill, null); field(quill.getClass(), "range").setInt(quill, 10);
            boolean baseline = quillCase(mc, "first_person");
            if (!baseline) throw new IllegalStateException("Quill first-person control failed; camera comparison inconclusive");
            quillCase(mc, "shoulder"); quillCase(mc, "freelook");
            baseline = zapperCase(mc, "first_person");
            if (!baseline) throw new IllegalStateException("Worldshaper first-person control failed; camera comparison inconclusive");
            zapperCase(mc, "shoulder"); zapperCase(mc, "freelook");
            if (focusedChecks) {
                quillCase(mc, "first_person_enabled"); quillCase(mc, "shoulder_left");
                zapperCase(mc, "first_person_enabled"); zapperCase(mc, "shoulder_left");
                for (String mode : new String[]{"first_person", "first_person_enabled", "shoulder", "shoulder_left", "freelook"})
                    quillBlockCase(mc, mode);
                zapperGapCase(mc, "shoulder"); zapperGapCase(mc, "shoulder_left");
            }
            restore(mc, "DONE");
        } catch (Throwable error) {
            failures++; log.accept("CREATE_SELECTION ERROR " + error); error.printStackTrace();
            try { restore(mc, "ABORT"); }
            catch (Throwable restoreError) { running = false; log.accept("CREATE_SELECTION RESTORE_ERROR " + restoreError); }
        }
    }

    private static boolean quillCase(Minecraft mc, String mode) throws Exception {
        mc.player.setItemInHand(InteractionHand.MAIN_HAND, item("create:schematic_and_quill"));
        boolean candidate = !mode.startsWith("first_person");
        float yaw = candidate ? 60 : 0;
        Vec3 direction = Vec3.directionFromRotation(0, yaw), eye = mc.player.getEyePosition();
        Vec3 origin = mode.startsWith("shoulder") ? eye.subtract(direction.scale(4)).add(mode.equals("shoulder_left") ? -.95 : .95, 0, 0) : eye;
        body(mc, 0, 0); mode(mc, mode, yaw, 0, origin);
        field(quill.getClass(), "selectedPos").set(quill, null);
        boolean pressed;
        try {
            // ACTIVATE_TOOL is raw Ctrl in this Create build. Substitute only
            // its input predicate; execute the original tick/selection branch.
            activateModifiable.setBoolean(activate, true); activateKeybind.set(activate, mc.options.keyUse);
            mc.options.keyUse.setDown(true);
            pressed = (boolean) activate.getClass().getMethod("isPressed").invoke(activate);
            quill.getClass().getMethod("tick").invoke(quill);
        } finally {
            activateModifiable.setBoolean(activate, oldModifiable); activateKeybind.set(activate, oldKeybind); mc.options.keyUse.setDown(oldUse);
        }
        BlockPos actual = (BlockPos) field(quill.getClass(), "selectedPos").get(quill);
        float partial = (float) Class.forName("net.createmod.catnip.animation.AnimationTickHolder").getMethod("getPartialTicks").invoke(null);
        BlockPos bodyExpected = BlockPos.containing(mc.player.getEyePosition(partial).add(mc.player.getLookAngle().scale(10)));
        boolean onCameraRay = actual != null && new AABB(actual).inflate(1e-6).clip(origin, origin.add(direction.scale(128))).isPresent();
        boolean pass = pressed && onCameraRay && (candidate || actual.equals(bodyExpected));
        Vec3 rangeOrigin = origin.add(direction.scale(Math.max(0, eye.subtract(origin).dot(direction))));
        BlockPos rawRangeExpected = candidate ? BlockPos.containing(rangeOrigin.add(direction.scale(10))) : bodyExpected;
        if (focusedChecks) pass &= rawRangeExpected.equals(actual);
        result("quill_free_selection", mode, pass, "activatePressed=" + pressed + " selected=" + actual + " bodyExpected=" + bodyExpected
                + " matchesBody=" + bodyExpected.equals(actual) + " selectedVoxelIntersectsCameraRay=" + onCameraRay
                + " cameraOrigin=" + origin + " cameraDirection=" + direction + " range=10 rawRangeExpected=" + rawRangeExpected);
        return pass;
    }

    private static boolean zapperCase(Minecraft mc, String mode) throws Exception {
        return zapperCase(mc, mode, "worldshaper_material_selection");
    }

    private static boolean zapperCase(Minecraft mc, String mode, String kind) throws Exception {
        ItemStack stack = item("create:handheld_worldshaper");
        mc.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        selectionPose(mc, mode);
        mc.gameRenderer.pick(1);
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(TARGET))
            throw new IllegalStateException("Worldshaper camera fixture did not pick gold: " + mc.hitResult);
        @SuppressWarnings("unchecked") DataComponentType<BlockState> component = (DataComponentType<BlockState>)
                Class.forName("com.simibubi.create.AllDataComponents").getField("SHAPER_BLOCK_USED").get(null);
        if (stack.has(component)) throw new IllegalStateException("Fresh worldshaper has a selected material");
        HitResult bodyPick = mc.player.pick(20, 1, false);
        boolean accepted = (boolean) Class.forName("com.simibubi.create.content.equipment.zapper.ZapperInteractionHandler")
                .getMethod("trySelect", ItemStack.class, Player.class).invoke(null, stack, mc.player);
        BlockState selected = stack.get(component);
        boolean pass = accepted && selected != null && selected.is(Blocks.GOLD_BLOCK);
        result(kind, mode, pass, "fresh=true accepted=" + accepted + " selected=" + selected
                + " expected=minecraft:gold_block cameraTarget=" + TARGET + " bodyHit="
                + (bodyPick instanceof BlockHitResult block ? block.getBlockPos() : bodyPick.getType()) + " scope=client_component_only");
        return pass;
    }

    private static Vec3[] selectionPose(Minecraft mc, String mode) throws Exception {
        Vec3 eye = mc.player.getEyePosition(), origin = mode.startsWith("shoulder") ? eye.add(mode.equals("shoulder_left") ? -.95 : .95, 0, -4) : eye;
        Vec3 direction = TARGET.getCenter().subtract(origin).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        body(mc, mode.startsWith("first_person") ? yaw : 0, mode.startsWith("first_person") ? pitch : 0);
        mode(mc, mode, yaw, pitch, origin);
        return new Vec3[]{origin, direction};
    }

    private static void quillBlockCase(Minecraft mc, String mode) throws Exception {
        mc.player.setItemInHand(InteractionHand.MAIN_HAND, item("create:schematic_and_quill"));
        selectionPose(mc, mode);
        field(quill.getClass(), "selectedPos").set(quill, null);
        try {
            activateModifiable.setBoolean(activate, true); activateKeybind.set(activate, mc.options.keyUse);
            mc.options.keyUse.setDown(false);
            quill.getClass().getMethod("tick").invoke(quill);
        } finally {
            activateModifiable.setBoolean(activate, oldModifiable); activateKeybind.set(activate, oldKeybind);
            mc.options.keyUse.setDown(oldUse);
        }
        BlockPos selected = (BlockPos) field(quill.getClass(), "selectedPos").get(quill);
        result("quill_block_selection", mode, TARGET.equals(selected), "selected=" + selected + " expected=" + TARGET
                + " scope=client_selection_only;raw_range=75;collider_shape");
    }

    private static void zapperGapCase(Minecraft mc, String mode) throws Exception {
        Vec3[] pose = selectionPose(mc, mode);
        BlockPos decoy = BlockPos.containing(pose[0].add(pose[1].scale(2)));
        if (decoy.equals(TARGET) || !mc.level.hasChunkAt(decoy))
            throw new IllegalStateException("Invalid camera-gap fixture " + decoy);
        BlockState before = mc.level.getBlockState(decoy);
        try {
            // Selection executes only on the client. This temporary local
            // collider is restored in the same tick; it is not server gameplay.
            mc.level.setBlock(decoy, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
            log.accept("CREATE_SELECTION FIXTURE cameraGapDecoy=" + decoy + " mode=" + mode + " client_only=true");
            zapperCase(mc, mode, "worldshaper_camera_gap_excluded");
        } finally {
            mc.level.setBlock(decoy, before, 18);
        }
    }

    private static void mode(Minecraft mc, String mode, float yaw, float pitch, Vec3 origin) throws Exception {
        boolean candidate = !mode.startsWith("first_person"), shoulder = mode.startsWith("shoulder");
        ClientConfig.ENABLE_FREELOOK.set(true); freelookKey.setDown(mode.equals("freelook"));
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        ClientConfig.TOGGLE_MOD.set(candidate || mode.equals("first_person_enabled")); field(UnlockedCameraClient.class, "active").setBoolean(null, shoulder);
        field(UnlockedCameraClient.class, "freelookYaw").setFloat(null, mode.equals("freelook") ? yaw : 0);
        field(UnlockedCameraClient.class, "freelookPitch").setFloat(null, mode.equals("freelook") ? pitch : 0);
        mc.options.setCameraType(shoulder ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        cameraPosition.invoke(mc.gameRenderer.getMainCamera(), origin); cameraRotation.invoke(mc.gameRenderer.getMainCamera(), yaw, pitch, 0f);
    }

    private static void body(Minecraft mc, float yaw, float pitch) throws Exception {
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object pose = rotation.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, pose); field(camera.getClass(), "playerRotation").set(camera, pose);
    }

    private static void restore(Minecraft mc, String status) throws Exception {
        activateModifiable.setBoolean(activate, oldModifiable); activateKeybind.set(activate, oldKeybind);
        freelookKey.setDown(oldFreelook); ClientConfig.ENABLE_FREELOOK.set(oldFreelookEnabled);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeepDirection);
        mc.options.keyUse.setDown(oldUse); mc.options.keyShift.setDown(oldShift); mc.player.setShiftKeyDown(oldShift); mc.player.input.shiftKeyDown = oldShift;
        for (var entry : quillFields.entrySet()) entry.getKey().set(quill, entry.getValue());
        for (var entry : cameraFields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
        mc.options.setCameraType(oldType); cameraPosition.invoke(mc.gameRenderer.getMainCamera(), oldCameraPosition);
        cameraRotation.invoke(mc.gameRenderer.getMainCamera(), oldCameraYaw, oldCameraPitch, oldCameraRoll);
        restoring = true; age = 0;
        task = server(mc, p -> {
            if (snapshot == null) return;
            if (fixtureReady) for (var entry : blocks.entrySet()) snapshot.level.setBlock(entry.getKey(), entry.getValue(), 3);
            for (int i = 0; i < snapshot.inventory.size(); i++) p.getInventory().setItem(i, snapshot.inventory.get(i));
            p.getInventory().selected = snapshot.selected; p.setGameMode(snapshot.mode); p.getAbilities().flying = snapshot.flying; p.onUpdateAbilities();
            p.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch); p.containerMenu.broadcastChanges();
        });
        log.accept("CREATE_SELECTION " + status + " checks=" + checks + " failures=" + failures + " restoration=pending");
    }

    private static ItemStack item(String id) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static CompletableFuture<?> server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
        return server.submit(() -> action.accept(server.getPlayerList().getPlayer(uuid)));
    }
    private static void result(String kind, String mode, boolean pass, String details) {
        checks++; if (!pass) failures++;
        log.accept("CREATE_SELECTION " + (pass ? "PASS " : "FAIL ") + kind + " " + mode + " " + details);
    }
}
