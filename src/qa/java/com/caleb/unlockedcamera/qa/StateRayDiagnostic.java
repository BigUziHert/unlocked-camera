package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.google.gson.JsonParser;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Narrow diagnostic of failed state rays; no production changes, no server mutation. */
public final class StateRayDiagnostic {
    private static final BlockPos TARGET = new BlockPos(1742, 150, 193);
    private static final Vec3 PLAYER = new Vec3(1742.5, 150, 190.5);
    private StateRayDiagnostic() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (mc.player == null || mc.level == null || mc.player.isUsingItem() || mc.player.isPassenger()
                || BlockInteractionProbe.isRunning())
            throw new IllegalStateException("State diagnostic needs an idle unmounted QA client");
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("State diagnostic restricted to disposable QA instances");
        Path cases = Path.of("qa", "state-diagnostic-cases.json");
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (Files.exists(cases)) {
            for (var value : JsonParser.parseString(Files.readString(cases)).getAsJsonArray()) requested.add(value.getAsString());
        } else {
            Path root = game.getParent().getParent().getParent();
            cases = root.resolve("build/modpack-block-test/game/qa/block-state-targeting.tsv");
            try (var lines = Files.lines(cases)) {
                lines.forEach(line -> {
                    String[] cells = line.split("\t", -1);
                    if (cells.length > 2 && (cells[1].equals("TARGETING_FAIL") || cells[1].equals("ERROR"))) requested.add(cells[0]);
                });
            }
        }
        if (requested.size() > 1000) throw new IllegalStateException("Diagnostic is limited to 1000 states; supply selected cases JSON");
        List<BlockState> states = new ArrayList<>();
        for (String description : requested) {
            int close = description.indexOf('}');
            if (!description.startsWith("Block{") || close < 0) throw new IllegalArgumentException(description);
            var id = ResourceLocation.parse(description.substring(6, close));
            if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Missing block " + id);
            BlockState state = BuiltInRegistries.BLOCK.get(id).getStateDefinition().getPossibleStates().stream()
                    .filter(s -> s.toString().equals(description)).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing state " + description));
            states.add(state);
        }
        Map<BlockPos, BlockState> savedBlocks = new LinkedHashMap<>();
        for (BlockPos mutable : BlockPos.betweenClosed(1738, 149, 185, 1746, 156, 198)) {
            BlockPos pos = mutable.immutable();
            if (!mc.level.hasChunkAt(pos) || mc.level.getBlockEntity(pos) != null)
                throw new IllegalStateException("Diagnostic fixture has unloaded chunk/block entity " + pos);
            savedBlocks.put(pos, mc.level.getBlockState(pos));
        }
        if (!mc.level.getEntities(mc.player, new AABB(1738, 149, 185, 1747, 157, 199), e -> e.isPickable()).isEmpty())
            throw new IllegalStateException("Diagnostic fixture contains pickable entities");
        Camera camera = mc.gameRenderer.getMainCamera();
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        position.setAccessible(true); rotation.setAccessible(true);
        Vec3 oldPlayer = mc.player.position(), oldCamera = camera.getPosition();
        float oldYaw = mc.player.getYRot(), oldPitch = mc.player.getXRot(), oldYawO = mc.player.yRotO, oldPitchO = mc.player.xRotO;
        float oldHead = mc.player.getYHeadRot(), oldHeadO = mc.player.yHeadRotO;
        float camYaw = camera.getYRot(), camPitch = camera.getXRot(), camRoll = camera.getRoll();
        CameraType oldType = mc.options.getCameraType();
        HitResult oldHit = mc.hitResult;
        boolean toggle = ClientConfig.TOGGLE_MOD.get(), shoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        double max = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        Map<Field, Object> savedFields = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(name); savedFields.put(field, field.get(null));
        }
        int explained = 0, differences = 0;
        Path output = Path.of("qa", "state-ray-diagnostics.tsv");
        log.accept("STATE_RAY_DIAGNOSTIC START states=" + states.size() + " source=" + cases);
        try (BufferedWriter out = Files.newBufferedWriter(output)) {
            out.write("state\tmode\tstatus\tfirst_person_positive\tactual_pick\tcore_pick\tvanilla_camera_clip\tvanilla_depth_clip\tdirect_shape_hit\toutline_bounds\torigin\tdirection\tpoint\n");
            mc.player.setPos(PLAYER.x, PLAYER.y, PLAYER.z);
            for (BlockPos pos : savedBlocks.keySet()) mc.level.setBlock(pos, pos.getY() == 149 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 0);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field("targetDistance").setFloat(null, 4); field("freelookYaw").setFloat(null, 0); field("freelookPitch").setFloat(null, 0);
            for (BlockState state : states) {
                mc.level.setBlock(TARGET, state, 0);
                var shape = state.getShape(mc.level, TARGET, CollisionContext.of(mc.player));
                if (shape.isEmpty()) throw new IllegalStateException("Failed state has empty outline " + state);
                AABB largest = shape.toAabbs().stream().max(Comparator.comparingDouble(b -> b.getXsize() * b.getYsize() * b.getZsize())).orElseThrow();
                Vec3 point = largest.getCenter().add(TARGET.getX(), TARGET.getY(), TARGET.getZ());
                boolean baseline = false;
                for (int view = 0; view < 3; view++) {
                    Vec3 eye = mc.player.getEyePosition(1);
                    Vec3 origin = view == 0 ? eye : eye.add(view == 1 ? -.95 : .95, 0, -4);
                    Vec3 ideal = point.subtract(origin).normalize();
                    float yaw = (float) (Math.toDegrees(Math.atan2(ideal.z, ideal.x)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.asin(ideal.y));
                    ClientConfig.TOGGLE_MOD.set(view != 0); field("active").setBoolean(null, view != 0);
                    mc.options.setCameraType(view == 0 ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
                    mc.player.setYRot(view == 0 ? yaw : 0); mc.player.yRotO = mc.player.getYRot();
                    mc.player.setYHeadRot(mc.player.getYRot()); mc.player.yHeadRotO = mc.player.getYRot();
                    mc.player.setXRot(view == 0 ? pitch : 0); mc.player.xRotO = mc.player.getXRot();
                    position.invoke(camera, origin); rotation.invoke(camera, yaw, pitch, 0f);
                    var vector = camera.getLookVector();
                    Vec3 direction = new Vec3(vector.x(), vector.y(), vector.z());
                    double reach = mc.player.blockInteractionRange();
                    Vec3 end = origin.add(direction.scale(Math.max(reach, mc.player.entityInteractionRange()) + origin.distanceTo(eye)));
                    Vec3 depth = origin.add(direction.scale(Math.max(0, eye.subtract(origin).dot(direction))));
                    BlockHitResult raw = mc.level.clip(new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                    BlockHitResult gap = mc.level.clip(new ClipContext(depth, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                    BlockHitResult exactShape = shape.clip(origin, end, TARGET);
                    HitResult core = UnlockedCameraClient.cameraRayPick(mc.player, reach, mc.player.entityInteractionRange(), 1);
                    mc.gameRenderer.pick(1);
                    HitResult actual = mc.hitResult;
                    if (view == 0) baseline = target(actual);
                    String status;
                    if (target(actual)) status = "PASS_TARGET";
                    else if (!baseline) status = "BASELINE_NOT_TARGETABLE";
                    else if (actual != null && actual.getType() == HitResult.Type.MISS
                            && raw.getType() == HitResult.Type.MISS && gap.getType() == HitResult.Type.MISS
                            && core != null && core.getType() == HitResult.Type.MISS) {
                        status = "MATCHING_VANILLA_CAMERA_RAY_MISS"; explained++;
                    } else if (same(actual, gap) && same(core, gap)) { status = "MATCHING_VANILLA_OCCLUSION"; explained++; }
                    else { status = "REVIEW_CAMERA_REFERENCE_DIFFERENCE"; differences++; }
                    out.write(clean(state) + "\t" + (view == 0 ? "first_person" : view == 1 ? "shoulder_left" : "shoulder_right")
                            + "\t" + status + "\t" + baseline + "\t" + describe(actual) + "\t" + describe(core)
                            + "\t" + describe(raw) + "\t" + describe(gap) + "\t" + describe(exactShape)
                            + "\t" + clean(shape.bounds()) + "\t" + origin + "\t" + direction + "\t" + point + "\n");
                }
            }
        } finally {
            for (var entry : savedBlocks.entrySet()) mc.level.setBlock(entry.getKey(), entry.getValue(), 0);
            mc.player.setPos(oldPlayer.x, oldPlayer.y, oldPlayer.z); mc.player.setYRot(oldYaw); mc.player.setXRot(oldPitch);
            mc.player.yRotO = oldYawO; mc.player.xRotO = oldPitchO;
            mc.player.setYHeadRot(oldHead); mc.player.yHeadRotO = oldHeadO;
            for (var entry : savedFields.entrySet()) entry.getKey().set(null, entry.getValue());
            ClientConfig.TOGGLE_MOD.set(toggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(shoulder); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(max);
            mc.options.setCameraType(oldType); mc.hitResult = oldHit;
            position.invoke(camera, oldCamera); rotation.invoke(camera, camYaw, camPitch, camRoll);
        }
        log.accept("STATE_RAY_DIAGNOSTIC DONE states=" + states.size() + " explainedSideCases=" + explained
                + " unresolvedSideCases=" + differences + " file=" + output + " scope=ray-geometry-not-machine-operation");
    }
    private static Field field(String name) throws Exception { Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static boolean target(HitResult hit) { return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(TARGET); }
    private static boolean same(HitResult a, HitResult b) {
        return a instanceof BlockHitResult x && b instanceof BlockHitResult y && a.getType() == b.getType()
                && x.getBlockPos().equals(y.getBlockPos()) && a.getLocation().distanceToSqr(b.getLocation()) < 1e-8;
    }
    private static String describe(HitResult hit) { return hit == null ? "null" : hit.getType() + "@" + hit.getLocation() + (hit instanceof BlockHitResult b ? ";" + b.getBlockPos() : ""); }
    private static String clean(Object value) { return String.valueOf(value).replace('\t', ' ').replace('\n', ' '); }
}
