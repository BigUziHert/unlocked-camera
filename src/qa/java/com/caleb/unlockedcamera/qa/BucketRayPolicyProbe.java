package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.BucketUseRayContext;
import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Restored, synchronous client-only ray controls; actual bucket actions are tested separately. */
public final class BucketRayPolicyProbe {
    private BucketRayPolicyProbe() {}
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (mc.level == null || mc.player == null || mc.player.isPassenger() || mc.player.isUsingItem()
                || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Bucket ray policy controls require the idle disposable client");
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) throw new IllegalStateException("Camera not initialized");
        int baseX = mc.player.blockPosition().getX(), baseZ = mc.player.blockPosition().getZ();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (BlockPos mutable : BlockPos.betweenClosed(baseX - 6, 174, baseZ - 5, baseX + 3, 177, baseZ + 7)) {
            BlockPos pos = mutable.immutable();
            if (!mc.level.hasChunkAt(pos) || mc.level.getBlockEntity(pos) != null)
                throw new IllegalStateException("Ray control fixture unloaded or contains a block entity: " + pos);
            blocks.put(pos, mc.level.getBlockState(pos));
        }
        Map<Field, Object> state = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(name); state.put(field, field.get(null));
        }
        KeyMapping key = (KeyMapping) field("FREELOOK_KEY").get(null); boolean oldKey = key.isDown();
        boolean oldToggle = ClientConfig.TOGGLE_MOD.get(), oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        boolean oldFree = ClientConfig.ENABLE_FREELOOK.get();
        double oldAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(), oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        CameraType oldType = mc.options.getCameraType();
        Vec3 oldPlayer = mc.player.position(), oldCamera = camera.getPosition();
        float oldYaw = mc.player.getYRot(), oldPitch = mc.player.getXRot(), oldYawO = mc.player.yRotO, oldPitchO = mc.player.xRotO;
        float oldCameraYaw = camera.getYRot(), oldCameraPitch = camera.getXRot(), oldCameraRoll = camera.getRoll();
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        Method pov = Item.class.getDeclaredMethod("getPlayerPOVHitResult", Level.class, Player.class, ClipContext.Fluid.class);
        position.setAccessible(true); rotation.setAccessible(true); pov.setAccessible(true);
        Path path = Path.of("qa", "bucket-use", "ray-policy-" + System.currentTimeMillis() + ".log");
        Files.createDirectories(path.getParent());
        StringBuilder evidence = new StringBuilder();
        Consumer<String> log = line -> { output.accept(line); evidence.append(line).append('\n'); };
        int cases = 0;
        log.accept("BUCKET_RAY_POLICY START scope=ray_geometry_and_context_only;actual_actions_separate file=" + path);
        try {
            mc.player.setPos(baseX + .5, 174, baseZ + .5);
            mc.player.setYRot(90); mc.player.yRotO = 90; mc.player.setXRot(0); mc.player.xRotO = 0;
            for (BlockPos pos : blocks.keySet()) mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 0);
            ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            ClientConfig.ENABLE_FREELOOK.set(true);
            field("targetDistance").setFloat(null, 4); field("freelookPitch").setFloat(null, 0);
            for (int mode = 0; mode < 2; mode++) {
                BlockPos wall = new BlockPos(baseX + (mode == 0 ? 1 : 0), 175, baseZ + 4);
                BlockPos source = wall.offset(0, 0, -2), flow = wall.offset(0, 0, -3);
                BlockPos gap = wall.offset(0, 0, -6), straddler = wall.offset(0, 0, -4);
                mc.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 0);
                String view = mode == 0 ? "shoulder" : "freelook";
                field("active").setBoolean(null, mode == 0);
                field("freelookYaw").setFloat(null, mode == 1 ? -90 : 0);
                key.setDown(mode == 1);
                mc.options.setCameraType(mode == 0 ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
                Vec3 eye = mc.player.getEyePosition();
                // The shoulder has lateral parallax. Held freelook originates
                // at the real eye while body yaw remains ninety degrees away.
                Vec3 origin = mode == 0 ? new Vec3(baseX + 1.5, eye.y, baseZ - 3.5) : eye;
                position.invoke(camera, origin); rotation.invoke(camera, 0f, 0f, 0f);
                mc.level.setBlock(source, Blocks.WATER.defaultBlockState(), 0);
                mc.level.setBlock(flow, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1), 0);
                require(mc.level.getBlockState(wall).is(Blocks.STONE) && mc.level.getFluidState(source).isSource()
                                && mc.level.getBlockState(flow).is(Blocks.WATER) && !mc.level.getFluidState(flow).isSource(),
                        view + ":fixture_readback_failed wall=" + mc.level.getBlockState(wall) + ";source=" + mc.level.getFluidState(source)
                                + ";flow=" + mc.level.getFluidState(flow));
                HitResult direct = mc.level.clip(new ClipContext(origin, origin.add(0, 0, 10), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
                HitResult core = UnlockedCameraClient.crosshairBlockPick(mc.player, mc.player.blockInteractionRange(), ClipContext.Fluid.SOURCE_ONLY);
                log.accept("BUCKET_RAY_POLICY DIAGNOSTIC mode=" + view + " origin=" + origin + " eye=" + eye + " reach=" + mc.player.blockInteractionRange()
                        + " source=" + source + " sourceFluid=" + mc.level.getFluidState(source) + " raw=" + describe(direct) + " core=" + describe(core));
                requireBlock(scoped(mc, pov, ClipContext.Fluid.SOURCE_ONLY), source, view + ":source_only_skips_flowing"); cases++;
                requireBlock(scoped(mc, pov, ClipContext.Fluid.ANY), flow, view + ":any_includes_flowing"); cases++;
                requireBlock(scoped(mc, pov, ClipContext.Fluid.NONE), wall, view + ":none_ignores_fluids"); cases++;
                require(raw(mc, pov, ClipContext.Fluid.SOURCE_ONLY).getType() == HitResult.Type.MISS,
                        view + ":outside_use_keeps_body_ray"); cases++;
                if (mode == 0) {
                    requestIsolation(mc, pov, source); cases += 8;
                    log.accept("BUCKET_RAY_POLICY PASS packetRequestScope=matched_hand_once_after_prediction nestedRequest=isolated"
                            + " thrownRequest=cleared nonBucketRequest=uncaptured removedFluid=captured_aim_retained");
                }
                mc.level.setBlock(source, Blocks.AIR.defaultBlockState(), 0);
                mc.level.setBlock(flow, Blocks.AIR.defaultBlockState(), 0);
                if (mode == 0) {
                    mc.level.setBlock(gap, Blocks.GLASS.defaultBlockState(), 0);
                    requireBlock(scoped(mc, pov, ClipContext.Fluid.NONE), wall, "shoulder:skip_fully_exited_gap_block"); cases++;
                    mc.level.setBlock(gap, Blocks.AIR.defaultBlockState(), 0);
                    mc.level.setBlock(straddler, Blocks.GLASS.defaultBlockState(), 0);
                    requireBlock(scoped(mc, pov, ClipContext.Fluid.NONE), straddler, "shoulder:retain_player_depth_straddler"); cases++;
                    mc.level.setBlock(straddler, Blocks.AIR.defaultBlockState(), 0);
                }
                double forward = wall.getZ() - eye.z;
                double eyeDistance = new Vec3(origin.x, origin.y, wall.getZ()).distanceTo(eye);
                double shortReach = mode == 0 ? (forward + eyeDistance) / 2 : eyeDistance - .02;
                require(UnlockedCameraClient.crosshairBlockPick(mc.player, shortReach, ClipContext.Fluid.NONE).getType() == HitResult.Type.MISS,
                        view + ":eye_boundary_rejects_beyond_reach"); cases++;
                requireBlock(UnlockedCameraClient.crosshairBlockPick(mc.player, eyeDistance + .02, ClipContext.Fluid.NONE), wall,
                        view + ":within_eye_boundary_positive_control"); cases++;
                log.accept("BUCKET_RAY_POLICY PASS mode=" + view + " sourceOnly=source any=flow none=wall outsideScope=bodyMiss"
                        + " eyeBoundary=reject_then_accept bodyYaw=90 cameraYaw=0 camera=" + origin
                        + " shortReach=" + shortReach + " eyeDistance=" + eyeDistance);
                mc.level.setBlock(wall, Blocks.AIR.defaultBlockState(), 0);
            }
            // Scope restoration must survive both an inner exception and an
            // outer exception; this is the isolation boundary for unrelated items.
            BucketUseRayContext.duringUse(mc.level, mc.player, () -> {
                require(BucketUseRayContext.matches(mc.level, mc.player), "outer_scope_active");
                BucketUseRayContext.outsideUse(() -> {
                    require(!BucketUseRayContext.matches(mc.level, mc.player), "nested_other_item_suspends_bucket_scope");
                    return null;
                });
                require(BucketUseRayContext.matches(mc.level, mc.player), "nested_other_item_restores_bucket_scope");
                try {
                    BucketUseRayContext.duringUse(mc.level, null, () -> { throw new IllegalArgumentException("QA nested sentinel"); });
                } catch (IllegalArgumentException expected) {
                    require(BucketUseRayContext.matches(mc.level, mc.player), "nested_exception_restores_outer_scope");
                }
                return null;
            });
            require(!BucketUseRayContext.matches(mc.level, mc.player), "normal_exit_clears_scope"); cases++;
            try {
                BucketUseRayContext.duringUse(mc.level, mc.player, () -> { throw new IllegalArgumentException("QA outer sentinel"); });
            } catch (IllegalArgumentException expected) {
                require(!BucketUseRayContext.matches(mc.level, mc.player), "exception_exit_clears_scope"); cases++;
            }
            field("active").setBoolean(null, false); field("freelookYaw").setFloat(null, 0); key.setDown(false);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            require(UnlockedCameraClient.crosshairBlockPick(mc.player, 4.5, ClipContext.Fluid.NONE) == null,
                    "inactive_camera_delegates"); cases++;
            require(scoped(mc, pov, ClipContext.Fluid.NONE).getType() == HitResult.Type.MISS,
                    "inactive_bucket_scope_keeps_body_ray"); cases++;
            log.accept("BUCKET_RAY_POLICY PASS controls=" + cases + " nestedException=restored outerException=cleared inactive=vanilla"
                    + " limits=world_geometry_only;Sable_uses_existing_shared_range_and_clip_path_not_new_ship_fixture");
        } catch (Throwable failure) {
            log.accept("BUCKET_RAY_POLICY FAIL " + failure); throw failure;
        } finally {
            for (var entry : blocks.entrySet()) mc.level.setBlock(entry.getKey(), entry.getValue(), 0);
            mc.player.setPos(oldPlayer); mc.player.setYRot(oldYaw); mc.player.yRotO = oldYawO;
            mc.player.setXRot(oldPitch); mc.player.xRotO = oldPitchO;
            for (var entry : state.entrySet()) entry.getKey().set(null, entry.getValue());
            key.setDown(oldKey); mc.options.setCameraType(oldType);
            ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
            ClientConfig.SHOULDER_OFFSET_AMOUNT.set(oldAmount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax);
            ClientConfig.ENABLE_FREELOOK.set(oldFree);
            position.invoke(camera, oldCamera); rotation.invoke(camera, oldCameraYaw, oldCameraPitch, oldCameraRoll);
            log.accept("BUCKET_RAY_POLICY RESTORED"); Files.writeString(path, evidence);
        }
    }
    private static BlockHitResult scoped(Minecraft mc, Method pov, ClipContext.Fluid fluid) {
        return BucketUseRayContext.duringUse(mc.level, mc.player, () -> raw(mc, pov, fluid));
    }
    private static void requestIsolation(Minecraft mc, Method pov, BlockPos source) {
        require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null, "no_request_no_packet_aim");
        BucketUseRayContext.duringRequest(mc.level, mc.player, InteractionHand.MAIN_HAND, () -> {
            final float[][] expected = new float[1][];
            BucketUseRayContext.duringUse(mc.level, mc.player, InteractionHand.MAIN_HAND, () -> {
                BlockHitResult hit = raw(mc, pov, ClipContext.Fluid.SOURCE_ONLY);
                requireBlock(hit, source, "request_capture_exact_source");
                expected[0] = UnlockedCameraClient.bucketHitAimAngles(mc.player, hit);
                require(expected[0] != null, "request_capture_positive_aim");
                BucketUseRayContext.duringUse(mc.level, mc.player, InteractionHand.MAIN_HAND, () -> {
                    BlockHitResult nested = raw(mc, pov, ClipContext.Fluid.NONE);
                    require(nested.getType() == HitResult.Type.BLOCK && !nested.getBlockPos().equals(source),
                            "nested_direct_bucket_fixture_has_different_target");
                    return null;
                });
                require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null,
                        "packet_aim_not_available_during_bucket_prediction");
                BucketUseRayContext.outsideUse(() -> {
                    require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null,
                            "nested_unrelated_stack_cannot_consume_outer_bucket_aim");
                    return null;
                });
                return null;
            });
            require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.OFF_HAND) == null, "wrong_hand_cannot_consume");
            BucketUseRayContext.duringRequest(mc.level, mc.player, InteractionHand.OFF_HAND, () -> {
                require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null, "nested_request_cannot_consume_outer");
                BucketUseRayContext.duringUse(mc.level, mc.player, InteractionHand.OFF_HAND,
                        () -> raw(mc, pov, ClipContext.Fluid.SOURCE_ONLY));
                require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.OFF_HAND) != null, "nested_request_own_capture");
                require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.OFF_HAND) == null, "nested_request_once_only");
                return null;
            });
            try {
                BucketUseRayContext.duringRequest(mc.level, mc.player, InteractionHand.OFF_HAND, () -> {
                    BucketUseRayContext.duringUse(mc.level, mc.player, InteractionHand.OFF_HAND,
                            () -> raw(mc, pov, ClipContext.Fluid.SOURCE_ONLY));
                    throw new IllegalArgumentException("QA request sentinel");
                });
            } catch (IllegalArgumentException expectedFailure) { /* Outer request must remain intact. */ }
            mc.level.setBlock(source, Blocks.AIR.defaultBlockState(), 0);
            try {
                float[] aim = BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND);
                require(java.util.Arrays.equals(aim, expected[0]), "captured_aim_survives_source_removal_and_nested_request");
                require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null, "outer_request_once_only");
            } finally {
                mc.level.setBlock(source, Blocks.WATER.defaultBlockState(), 0);
            }
            return null;
        });
        require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null, "request_exit_clears_packet_aim");
        BucketUseRayContext.duringRequest(mc.level, mc.player, InteractionHand.MAIN_HAND, () -> {
            raw(mc, pov, ClipContext.Fluid.SOURCE_ONLY); // A request alone is not bucket use.
            require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null, "non_bucket_request_never_captures");
            try {
                BucketUseRayContext.duringUse(mc.level, mc.player, InteractionHand.MAIN_HAND, () -> {
                    raw(mc, pov, ClipContext.Fluid.SOURCE_ONLY);
                    throw new IllegalArgumentException("QA failed bucket sentinel");
                });
            } catch (IllegalArgumentException expectedFailure) { /* Failed prediction must not publish an aim. */ }
            require(BucketUseRayContext.consumePacketAim(mc.player, InteractionHand.MAIN_HAND) == null, "failed_bucket_prediction_discards_aim");
            return null;
        });
    }
    private static BlockHitResult raw(Minecraft mc, Method pov, ClipContext.Fluid fluid) {
        try { return (BlockHitResult) pov.invoke(null, mc.level, mc.player, fluid); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static Field field(String name) throws Exception {
        Field field = UnlockedCameraClient.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void requireBlock(HitResult hit, BlockPos expected, String context) {
        require(hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(expected),
                context + " actual=" + hit.getType() + ":" + (hit instanceof BlockHitResult block ? block.getBlockPos() : hit.getLocation()));
    }
    private static String describe(HitResult hit) {
        return hit == null ? "null" : hit.getType() + ":" + (hit instanceof BlockHitResult block ? block.getBlockPos() : hit.getLocation());
    }
    private static void require(boolean condition, String context) {
        if (!condition) throw new AssertionError(context);
    }
}
