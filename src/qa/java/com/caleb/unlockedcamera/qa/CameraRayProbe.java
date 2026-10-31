package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Synchronous real-level pick/collision regressions, only in the opt-in QA build. */
public final class CameraRayProbe {
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) throw new IllegalStateException("Camera not initialized");
        Vec3 eye = mc.player.getEyePosition();
        int x = (int) Math.floor(eye.x + 0.95);
        int y = (int) Math.floor(eye.y);
        int z = (int) Math.floor(eye.z);
        AABB fixture = new AABB(x - 1, y - 1, z - 5, x + 2, y + 3, z + 10);
        if (!mc.level.getEntities(mc.player, fixture, Entity::isPickable).isEmpty()) {
            log.accept("CAMERARAY NEEDS_CLEAR_SPACE pickable entities already occupy fixture " + fixture);
            return;
        }
        Map<BlockPos, BlockState> savedBlocks = new LinkedHashMap<>();
        for (BlockPos mutable : BlockPos.betweenClosed(x - 1, y - 1, z - 5, x + 1, y + 2, z + 9)) {
            BlockPos pos = mutable.immutable();
            if (!mc.level.hasChunkAt(pos) || mc.level.getBlockEntity(pos) != null) {
                log.accept("CAMERARAY NEEDS_CLEAR_SPACE unloaded chunk or block entity at " + pos);
                return;
            }
            savedBlocks.put(pos, mc.level.getBlockState(pos));
        }
        Vec3 oldCameraPos = camera.getPosition();
        float oldYaw = camera.getYRot(), oldPitch = camera.getXRot(), oldRoll = camera.getRoll();
        CameraType oldType = mc.options.getCameraType();
        boolean oldToggle = ClientConfig.TOGGLE_MOD.get();
        boolean oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        double oldMaxZoom = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        Map<Field, Object> savedState = new LinkedHashMap<>();
        for (String name : new String[] {"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(UnlockedCameraClient.class, name);
            savedState.put(field, field.get(null));
        }
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        position.setAccessible(true);
        rotation.setAccessible(true);
        Method visual = UnlockedCameraClient.class.getDeclaredMethod("cameraVisualClip",
                BlockGetter.class, Vec3.class, Vec3.class, Entity.class, double.class);
        visual.setAccessible(true);
        Cow cow = null;
        try {
            ClientConfig.TOGGLE_MOD.set(true);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            set("active", true);
            set("targetDistance", 4.0f);
            set("freelookYaw", 0.0f);
            set("freelookPitch", 0.0f);
            rotation.invoke(camera, 0.0f, 0.0f, 0.0f); // +Z, level ray
            for (BlockPos pos : savedBlocks.keySet()) mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 0);

            BlockPos target = new BlockPos(x, y, z + 3);
            BlockState[] states = {
                    Blocks.STONE.defaultBlockState(),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                    Blocks.GLASS.defaultBlockState(),
                    Blocks.OAK_LEAVES.defaultBlockState(),
                    Blocks.BAMBOO.defaultBlockState()
            };
            Vec3 ordinaryOrigin = new Vec3(x + 0.5, y + 0.75, eye.z - 4.0);
            for (BlockState state : states) {
                mc.level.setBlock(target, state, 0);
                // Bamboo has a position-dependent shape offset. Aim through the
                // installed block's real outline instead of assuming cell center.
                double rayX = state.is(Blocks.BAMBOO)
                        ? x + state.getShape(mc.level, target, CollisionContext.of(mc.player)).bounds().getCenter().x
                        : x + 0.5;
                Vec3 origin = new Vec3(rayX, y + 0.75, eye.z - 4.0);
                position.invoke(camera, origin);
                HitResult hit = UnlockedCameraClient.cameraRayPick(mc.player, 4.5, 4.5, 1.0f);
                requireBlock(hit, target, state.toString());
                Vec3 end = origin.add(0.0, 0.0, 12.0);
                HitResult ordinaryVisual = mc.level.clip(new ClipContext(origin, end,
                        ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
                HitResult cameraVisual = (HitResult) visual.invoke(null, mc.level, origin, end, mc.player, 196.0);
                if (state.is(Blocks.BAMBOO)) {
                    require(cameraVisual.getType() == HitResult.Type.MISS, "bamboo camera transparency");
                } else {
                    require(cameraVisual.getType() == ordinaryVisual.getType()
                                    && cameraVisual.getLocation().distanceToSqr(ordinaryVisual.getLocation()) < 1.0E-8,
                            "visual collision parity for " + state);
                }
                log.accept("CAMERARAY block=" + state + " picked=" + describe(hit)
                        + " visual=" + cameraVisual.getType());
                mc.level.setBlock(target, Blocks.AIR.defaultBlockState(), 0);
            }

            position.invoke(camera, ordinaryOrigin);
            mc.level.setBlock(target, Blocks.STONE.defaultBlockState(), 0);
            BlockPos gap = new BlockPos(x, y, z - 2);
            mc.level.setBlock(gap, Blocks.GLASS.defaultBlockState(), 0);
            requireBlock(UnlockedCameraClient.cameraRayPick(mc.player, 4.5, 4.5, 1.0f), target,
                    "glass entirely behind player must be skipped");
            mc.level.setBlock(gap, Blocks.AIR.defaultBlockState(), 0);

            // A ray can reach this block geometrically but its lateral offset
            // puts the hit outside eye-based reach. The range filter must reject it.
            double forwardReach = target.getZ() - eye.z;
            double eyeReach = new Vec3(ordinaryOrigin.x, ordinaryOrigin.y, target.getZ()).distanceTo(eye);
            double narrowReach = (forwardReach + eyeReach) * 0.5;
            HitResult raw = mc.level.clip(new ClipContext(ordinaryOrigin,
                    ordinaryOrigin.add(0.0, 0.0, narrowReach + ordinaryOrigin.distanceTo(eye)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            requireBlock(raw, target, "reach fixture intersects raw ray");
            HitResult tooFar = UnlockedCameraClient.cameraRayPick(mc.player, narrowReach, narrowReach, 1.0f);
            require(tooFar.getType() == HitResult.Type.MISS, "eye-based reach rejects lateral extension");
            log.accept("CAMERARAY gap-glass=PASS eye-reach=PASS range=" + narrowReach);

            cow = EntityType.COW.create(mc.level);
            if (cow == null) throw new IllegalStateException("Cannot create QA cow");
            cow.setPos(x + 0.5, y, z + 2.0);
            mc.level.addEntity(cow);
            HitResult entity = UnlockedCameraClient.cameraRayPick(mc.player, 4.5, 4.5, 1.0f);
            require(entity instanceof EntityHitResult entityHit && entityHit.getEntity() == cow,
                    "near entity beats block behind it");
            BlockPos occluder = new BlockPos(x, y, z + 1);
            mc.level.setBlock(occluder, Blocks.STONE.defaultBlockState(), 0);
            requireBlock(UnlockedCameraClient.cameraRayPick(mc.player, 4.5, 4.5, 1.0f), occluder,
                    "wall occludes entity");
            mc.level.setBlock(occluder, Blocks.AIR.defaultBlockState(), 0);
            cow.setPos(x + 0.5, y, z - 1.5);
            // Refresh the section index synchronously; Entity#setPos notifies
            // the client entity manager's callback, no tick or packet is needed.
            requireBlock(UnlockedCameraClient.cameraRayPick(mc.player, 4.5, 4.5, 1.0f), target,
                    "entity behind player is excluded");
            log.accept("CAMERARAY entity-nearest=PASS entity-occlusion=PASS gap-entity=PASS");

            // With the camera inactive, the override must leave vanilla picking
            // in control rather than return a stale camera result.
            set("active", false);
            require(UnlockedCameraClient.cameraRayPick(mc.player, 4.5, 4.5, 1.0f) == null,
                    "inactive camera delegates picking");
            log.accept("CAMERARAY PASS 5 block outlines, visual collision parity/bamboo transparency,"
                    + " gap filtering, eye-based reach, entity precedence/occlusion, inactive delegation");
        } finally {
            if (cow != null) mc.level.removeEntity(cow.getId(), Entity.RemovalReason.DISCARDED);
            for (Map.Entry<BlockPos, BlockState> entry : savedBlocks.entrySet()) {
                mc.level.setBlock(entry.getKey(), entry.getValue(), 0);
            }
            ClientConfig.TOGGLE_MOD.set(oldToggle);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMaxZoom);
            mc.options.setCameraType(oldType);
            for (Map.Entry<Field, Object> entry : savedState.entrySet()) entry.getKey().set(null, entry.getValue());
            rotation.invoke(camera, oldYaw, oldPitch, oldRoll);
            position.invoke(camera, oldCameraPos);
        }
    }

    private static String describe(HitResult hit) {
        return hit.getType() + "@" + hit.getLocation();
    }

    private static void requireBlock(HitResult hit, BlockPos pos, String context) {
        require(hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                        && block.getBlockPos().equals(pos), context + " actual=" + describe(hit));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(String name, Object value) throws Exception {
        field(UnlockedCameraClient.class, name).set(null, value);
    }

    private static void require(boolean condition, String context) {
        if (!condition) throw new AssertionError("CAMERARAY FAIL " + context);
    }
}
