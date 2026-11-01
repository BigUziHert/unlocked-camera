package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.BucketUseRayContext;
import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real integrated-server caller guards; no server world, inventory, or pose writes. */
public final class ServerIsolationProbe {
    private static final Map<Field, Object> fields = new LinkedHashMap<>();
    private static Consumer<String> log;
    private static CompletableFuture<List<String>> task;
    private static boolean registered, running, oldToggle, oldShoulder, oldFree, oldKeep, oldKey;
    private static double oldMax;
    private static CameraType oldType;
    private static KeyMapping key;
    private static int mode, age, checks;

    private ServerIsolationProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        if (running || mc.player == null || mc.getSingleplayerServer() == null || mc.player.isUsingItem()
                || mc.player.isPassenger() || mc.screen != null
                || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Server isolation requires the idle disposable integrated client");
        log = output; fields.clear(); task = null; mode = age = checks = 0;
        for (String name : new String[]{"active", "targetDistance", "smoothedDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(name); fields.put(field, field.get(null));
        }
        key = (KeyMapping) field("FREELOOK_KEY").get(null); oldKey = key.isDown();
        oldType = mc.options.getCameraType(); oldToggle = ClientConfig.TOGGLE_MOD.get();
        oldShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get(); oldMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        oldFree = ClientConfig.ENABLE_FREELOOK.get(); oldKeep = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        if (!registered) { NeoForge.EVENT_BUS.addListener(ServerIsolationProbe::tick); registered = true; }
        running = true;
        pose(mc);
        log.accept("SERVER_ISOLATION START scope=real_ServerPlayer_read_only_guards;dedicated_login_not_executed;no_server_mod_required");
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (task != null) {
                if (!task.isDone()) return;
                List<String> rows = task.join(); task = null;
                rows.forEach(log); checks += rows.size();
                if (++mode == 2) { restore(mc, "DONE"); return; }
                age = 0; pose(mc); return;
            }
            if (++age < 3) return;
            if (UnlockedCameraClient.crosshairRayGapFreeOrigin(mc.player) == null)
                throw new IllegalStateException("Requested local camera mode is not active");
            var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
            String view = mode == 0 ? "offset" : "freelook";
            task = server.submit(() -> inspect(server.getPlayerList().getPlayer(uuid), view));
        } catch (Throwable error) {
            log.accept("SERVER_ISOLATION ERROR " + error);
            try { restore(mc, "ABORT"); }
            catch (Exception restoration) { running = false; log.accept("SERVER_ISOLATION RESTORE_ERROR " + restoration); }
        }
    }

    private static List<String> inspect(ServerPlayer player, String view) {
        if (player == null || player.level().isClientSide) throw new IllegalStateException("Real ServerPlayer missing");
        List<String> rows = new ArrayList<>();
        double range = player.blockInteractionRange(); Vec3 eye = player.getEyePosition();
        check(rows, view, "camera_origin", UnlockedCameraClient.crosshairRayOrigin(player) == null);
        check(rows, view, "gap_free_origin", UnlockedCameraClient.crosshairRayGapFreeOrigin(player) == null);
        check(rows, view, "camera_direction", UnlockedCameraClient.crosshairRayDirection(player) == null);
        check(rows, view, "camera_setback", UnlockedCameraClient.crosshairRaySetback(player) == 0);
        check(rows, view, "item_pick", UnlockedCameraClient.crosshairPick(player, range, 1, false) == null);
        check(rows, view, "create_target", UnlockedCameraClient.createTraceTarget(player, range, eye) == null);
        check(rows, view, "contraption_target", UnlockedCameraClient.contraptionRayTarget(player, range, eye) == null);
        check(rows, view, "bucket_server_scope", !BucketUseRayContext.duringUse(player.level(), player,
                () -> BucketUseRayContext.matches(player.level(), player)));
        for (ClipContext.Fluid fluid : ClipContext.Fluid.values()) {
            check(rows, view, "bucket_override_" + fluid, UnlockedCameraClient.crosshairBlockPick(player, range, fluid) == null);
            // Match this installed Item method's actual body-angle calculation,
            // not head interpolation or the client's displayed camera vector.
            Vec3 end = eye.add(player.calculateViewVector(player.getXRot(), player.getYRot()).scale(range));
            BlockHitResult expected = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, player));
            BlockHitResult actual = BucketUseRayContext.duringUse(player.level(), player,
                    () -> Item.getPlayerPOVHitResult(player.level(), player, fluid));
            check(rows, view, "bucket_body_pov_" + fluid, expected.getType() == actual.getType()
                    && expected.getBlockPos().equals(actual.getBlockPos()) && expected.getDirection() == actual.getDirection()
                    && expected.getLocation().distanceToSqr(actual.getLocation()) < 1.0E-12);
        }
        return rows;
    }

    private static void pose(Minecraft mc) throws Exception {
        ClientConfig.TOGGLE_MOD.set(true); ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5); ClientConfig.ENABLE_FREELOOK.set(true);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        field("active").setBoolean(null, mode == 0); field("targetDistance").setFloat(null, 4);
        field("smoothedDistance").setFloat(null, 4); field("freelookYaw").setFloat(null, mode == 1 ? 60 : 0);
        field("freelookPitch").setFloat(null, 0); key.setDown(mode == 1);
        mc.options.setCameraType(mode == 0 ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
    }

    private static void restore(Minecraft mc, String status) throws Exception {
        for (var entry : fields.entrySet()) entry.getKey().set(null, entry.getValue());
        ClientConfig.TOGGLE_MOD.set(oldToggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(oldShoulder);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(oldMax); ClientConfig.ENABLE_FREELOOK.set(oldFree);
        ClientConfig.FREELOOK_KEEP_DIRECTION.set(oldKeep); key.setDown(oldKey); mc.options.setCameraType(oldType);
        running = false;
        log.accept("SERVER_ISOLATION " + status + " checks=" + checks);
        log.accept("SERVER_ISOLATION RESTORED client_controls=true server_world_inventory_pose_written=false");
    }

    private static void check(List<String> rows, String mode, String label, boolean pass) {
        if (!pass) throw new IllegalStateException(mode + " " + label + " altered a server caller");
        rows.add("SERVER_ISOLATION PASS " + mode + " " + label);
    }

    private static Field field(String name) throws Exception {
        Field field = UnlockedCameraClient.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
