package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Uses an existing copied-world Sable seat: real plot hits, mount and camera handoff. */
public final class SableSceneProbe {
    private static Consumer<String> log;
    private static boolean running;
    private static boolean started;
    private static int ticks;
    private static volatile Fixture fixture;
    private static volatile Snapshot snapshot;
    private static volatile String error;
    private static CameraType cameraType;
    private static boolean toggle, shoulder, active;
    private static double amount, maxZoom;
    private static float distance;
    private static float pitch = 38.65981f;
    private record Fixture(BlockPos plotSeat, Vec3 worldSeat) {}
    private record Snapshot(Vec3 position, float yaw, float pitch, GameType mode, boolean flying,
                            ItemStack main, ItemStack off) {}

    private SableSceneProbe() {}

    public static void start(Minecraft mc, Consumer<String> sink) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null) throw new IllegalStateException("Sable probe needs idle singleplayer");
        log = sink; ticks = 0; fixture = null; snapshot = null; error = null; started = false;
        cameraType = mc.options.getCameraType();
        toggle = ClientConfig.TOGGLE_MOD.get(); shoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        amount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get(); maxZoom = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        active = field("active").getBoolean(null); distance = field("targetDistance").getFloat(null);
        running = true;
        log.accept("SABLE_SCENE START");
        var server = mc.getSingleplayerServer(); UUID id = mc.player.getUUID();
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) throw new IllegalStateException("Player absent");
                snapshot = new Snapshot(player.position(), player.getYRot(), player.getXRot(), player.gameMode.getGameModeForPlayer(),
                        player.getAbilities().flying, player.getMainHandItem().copy(), player.getOffhandItem().copy());
                Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                Object container = containerClass.getMethod("getContainer", Level.class).invoke(null, player.serverLevel());
                List<?> ships = (List<?>) containerClass.getMethod("getAllSubLevels").invoke(container);
                Fixture found = null;
                for (Object ship : ships) {
                    Object pose = ship.getClass().getMethod("logicalPose").invoke(ship);
                    Method transform = pose.getClass().getMethod("transformPosition", Vec3.class);
                    Object plot = ship.getClass().getMethod("getPlot").invoke(ship);
                    Collection<?> holders = (Collection<?>) plot.getClass().getMethod("getLoadedChunks").invoke(plot);
                    for (Object holder : holders) {
                        LevelChunk chunk = (LevelChunk) holder.getClass().getMethod("getChunk").invoke(holder);
                        for (int si = 0; si < chunk.getSections().length && found == null; si++) {
                            var section = chunk.getSection(si);
                            if (section.hasOnlyAir()) continue;
                            for (int y = 0; y < 16 && found == null; y++) for (int z = 0; z < 16 && found == null; z++) for (int x = 0; x < 16; x++) {
                                String block = BuiltInRegistries.BLOCK.getKey(section.getBlockState(x, y, z).getBlock()).toString();
                                if (!block.startsWith("create:") || !block.endsWith("_seat")) continue;
                                BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x,
                                        player.serverLevel().getSectionYFromSectionIndex(si) * 16 + y, chunk.getPos().getMinBlockZ() + z);
                                Vec3 world = (Vec3) transform.invoke(pose, new Vec3(pos.getX() + .5, pos.getY() + .25, pos.getZ() + .5));
                                found = new Fixture(pos, world); break;
                            }
                        }
                        if (found != null) break;
                    }
                    if (found != null) break;
                }
                if (found == null) throw new IllegalStateException("No loaded Create seat on a Sable sublevel");
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true; player.onUpdateAbilities();
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                player.containerMenu.broadcastChanges();
                Vec3 seat = found.worldSeat;
                player.teleportTo(seat.x + .95, seat.y + .3, seat.z - 2.4);
                player.setYRot(0); player.setXRot(pitch);
                fixture = found;
                log.accept("SABLE_SCENE FIXTURE plot=" + found.plotSeat + " world=" + found.worldSeat);
            } catch (Throwable failure) { error = failure.toString(); }
        });
    }

    public static void tick(Minecraft mc) {
        if (!running) return;
        try {
            if (mc.player == null || mc.getSingleplayerServer() == null) { running = false; return; }
            if (error != null) throw new IllegalStateException(error);
            if (fixture == null) {
                if (++ticks > 200) throw new IllegalStateException("Timed out finding server sublevel");
                return;
            }
            if (!started) { started = true; ticks = 0; }
            ++ticks;
            if (ticks < 110) {
                mc.player.setYRot(0); mc.player.yRotO = 0;
                mc.player.setXRot(pitch); mc.player.xRotO = pitch;
                btpFacing();
            }
            if (ticks == 60) {
                ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
                ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
                field("active").setBoolean(null, true); field("targetDistance").setFloat(null, 4);
                field("smoothedDistance").setFloat(null, 4); field("shoulderSide").setInt(null, 1);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            if (ticks == 90) {
                mc.gameRenderer.pick(1);
                HitResult hit = mc.hitResult;
                boolean picked = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(fixture.plotSeat);
                log.accept("SABLE_SCENE PICK " + (picked ? "PASS" : "FAIL") + " expected=" + fixture.plotSeat
                        + " actual=" + (hit instanceof BlockHitResult b ? b.getBlockPos() : hit)
                        + " position=" + mc.player.position() + " camera=" + mc.gameRenderer.getMainCamera().getPosition());
                HitResult ping = (HitResult) Class.forName("nx.pingwheel.common.math.Raycast")
                        .getMethod("traceDirectional", Vec3.class, float.class, double.class, boolean.class)
                        .invoke(null, UnlockedCameraClient.crosshairRayDirection(mc.player), 1f, 6d, true);
                boolean pinged = ping instanceof BlockHitResult b && ping.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(fixture.plotSeat);
                log.accept("SABLE_SCENE PING_RAY " + (pinged ? "PASS" : "FAIL") + " actual="
                        + (ping instanceof BlockHitResult b ? b.getBlockPos() : ping));
                float[] aim = UnlockedCameraClient.crosshairAimAngles();
                boolean finite = aim != null && Float.isFinite(aim[0]) && Float.isFinite(aim[1]) && aim[2] < 100;
                log.accept("SABLE_SCENE AIM " + (finite ? "PASS" : "FAIL") + " aim=" + java.util.Arrays.toString(aim));
            }
            if (ticks == 100) {
                mc.gameRenderer.pick(1);
                if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(fixture.plotSeat)) {
                    log.accept("SABLE_SCENE MOUNT_REQUEST " + mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit));
                } else log.accept("SABLE_SCENE MOUNT FAIL target missing");
            }
            if (ticks == 125) {
                Class<?> bridge = Class.forName("com.caleb.unlockedcamera.client.SableCompat");
                Method riding = bridge.getDeclaredMethod("isRidingSubLevel", net.minecraft.world.entity.Entity.class);
                riding.setAccessible(true);
                boolean seated = (boolean) riding.invoke(null, mc.player);
                boolean handedOff = !field("active").getBoolean(null);
                log.accept("SABLE_SCENE SEAT " + (seated && handedOff ? "PASS" : "FAIL") + " seated=" + seated
                        + " active=" + !handedOff + " cameraType=" + mc.options.getCameraType());
                mc.options.keyShift.setDown(true);
            }
            if (ticks == 135) mc.options.keyShift.setDown(false);
            if (ticks == 155) {
                log.accept("SABLE_SCENE DISMOUNT " + (!mc.player.isPassenger() ? "PASS" : "FAIL")
                        + " active=" + field("active").getBoolean(null) + " cameraType=" + mc.options.getCameraType());
                finish(mc, "DONE");
            }
        } catch (Throwable failure) {
            log.accept("SABLE_SCENE ERROR " + failure); failure.printStackTrace();
            try { finish(mc, "ABORT"); } catch (Exception e) { log.accept("SABLE_SCENE RESTORE_ERROR " + e); }
        }
    }

    private static void finish(Minecraft mc, String state) throws Exception {
        running = false; mc.options.keyShift.setDown(false);
        ClientConfig.TOGGLE_MOD.set(toggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(shoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(amount); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(maxZoom);
        field("active").setBoolean(null, active); field("targetDistance").setFloat(null, distance);
        mc.options.setCameraType(cameraType);
        var server = mc.getSingleplayerServer(); UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            Snapshot saved = snapshot;
            if (player == null || saved == null) return;
            player.stopRiding(); player.setGameMode(saved.mode); player.getAbilities().flying = saved.flying; player.onUpdateAbilities();
            player.setItemInHand(InteractionHand.MAIN_HAND, saved.main); player.setItemInHand(InteractionHand.OFF_HAND, saved.off);
            player.teleportTo(saved.position.x, saved.position.y, saved.position.z);
            player.setYRot(saved.yaw); player.setXRot(saved.pitch); player.containerMenu.broadcastChanges();
        });
        log.accept("SABLE_SCENE " + state);
    }

    private static void btpFacing() throws Exception {
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object value = rotation.getMethod("withPitch", float.class).invoke(rotation.getField("ZERO").get(null), pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, value);
        Field playerRotation = camera.getClass().getDeclaredField("playerRotation"); playerRotation.setAccessible(true); playerRotation.set(camera, value);
    }
    private static Field field(String name) throws Exception {
        Field field = UnlockedCameraClient.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
