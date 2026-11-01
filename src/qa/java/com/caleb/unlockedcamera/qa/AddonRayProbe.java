package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Diagnose loaded addon rays. These checks do not claim completed machine operations. */
public final class AddonRayProbe {
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Class<?> sockets;
        try { sockets = Class.forName("com.mred231.aeroworks.content.controls.socket.SocketPlacementClient"); }
        catch (ClassNotFoundException absent) { log.accept("ADDON_RAY SKIP aeroworks absent"); return; }
        Method eyeRay = sockets.getDeclaredMethod("eyeRay", Player.class);
        eyeRay.setAccessible(true);
        Camera camera = mc.gameRenderer.getMainCamera();
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        position.setAccessible(true); rotation.setAccessible(true);
        Map<Field, Object> saved = new LinkedHashMap<>();
        for (String name : new String[]{"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field field = field(name); saved.put(field, field.get(null));
        }
        Vec3 oldPosition = camera.getPosition();
        float yaw = camera.getYRot(), pitch = camera.getXRot(), roll = camera.getRoll();
        float bodyYaw = mc.player.getYRot(), bodyPitch = mc.player.getXRot();
        float bodyYawOld = mc.player.yRotO, bodyPitchOld = mc.player.xRotO;
        CameraType type = mc.options.getCameraType();
        boolean toggle = ClientConfig.TOGGLE_MOD.get(), shoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        double max = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        try {
            mc.player.setYRot(0); mc.player.setXRot(0); mc.player.yRotO = 0; mc.player.xRotO = 0;
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true); ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field("targetDistance").set(null, 4f);
            for (String mode : new String[]{"first_person", "shoulder", "freelook"}) {
                boolean active = !mode.equals("first_person");
                boolean free = mode.equals("freelook");
                ClientConfig.TOGGLE_MOD.set(active);
                field("active").set(null, active && !free);
                field("freelookYaw").set(null, free ? 60f : 0f);
                field("freelookPitch").set(null, 0f);
                mc.options.setCameraType(mode.equals("shoulder") ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
                Vec3 direction = Vec3.directionFromRotation(0, active ? 60 : 0);
                Vec3 eye = mc.player.getEyePosition();
                rotation.invoke(camera, active ? 60f : 0f, 0f, 0f);
                position.invoke(camera, mode.equals("shoulder") ? eye.subtract(direction.scale(4)).add(.75, 0, 0) : eye);
                Object ray = eyeRay.invoke(null, mc.player);
                Method fromGetter = ray.getClass().getDeclaredMethod("from"), toGetter = ray.getClass().getDeclaredMethod("to");
                fromGetter.setAccessible(true); toGetter.setAccessible(true);
                Vec3 from = (Vec3) fromGetter.invoke(ray), to = (Vec3) toGetter.invoke(ray);
                Vec3 actual = to.subtract(from).normalize();
                Vec3 expectedOrigin = active ? UnlockedCameraClient.crosshairRayGapFreeOrigin(mc.player) : eye;
                double dot = actual.dot(direction), originError = from.distanceTo(expectedOrigin);
                boolean correct = dot > .99999 && originError < .0001;
                log.accept("ADDON_RAY aeroworks_socket " + mode + " " + (correct ? "PASS" : "FAIL")
                        + " directionDot=" + dot + " originError=" + originError
                        + " actual=" + actual + " expected=" + direction + " scope=loaded_private_eyeRay_no_module_operation");
            }
        } finally {
            for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
            ClientConfig.TOGGLE_MOD.set(toggle); ClientConfig.ENABLE_SHOULDER_OFFSET.set(shoulder);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(max); mc.options.setCameraType(type);
            mc.player.setYRot(bodyYaw); mc.player.setXRot(bodyPitch); mc.player.yRotO = bodyYawOld; mc.player.xRotO = bodyPitchOld;
            position.invoke(camera, oldPosition); rotation.invoke(camera, yaw, pitch, roll);
        }
        log.accept("ADDON_RAY DONE");
    }
    private static Field field(String name) throws Exception {
        Field f = UnlockedCameraClient.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
