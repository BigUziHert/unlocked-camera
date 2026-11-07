package com.caleb.unlockedcamera.client;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** The fire_gun payload carries base aim. Iron's applies recoil and spread on the server. */
public final class IronsArtificeAim {
    private static float renderedYawKick;
    private static float renderedPitchKick;

    private IronsArtificeAim() {}

    public static void recordRenderedKick(float yawDelta, float pitchDelta) {
        renderedYawKick = yawDelta;
        renderedPitchKick = pitchDelta;
    }

    public static Vec3 fireDirection(Vec3 original) {
        float[] aim = UnlockedCameraClient.crosshairAimAngles();
        if (aim == null) return original;

        // Converge from the eye to the displayed target first. The camera already
        // includes Iron's visual kick; the server adds its counterpart to this
        // packet. Permanent recoil is part of the player's aim and stays in it.
        float pitch = Mth.clamp(aim[1] - renderedPitchKick, -90.0f, 90.0f);
        return Vec3.directionFromRotation(pitch, aim[0] - renderedYawKick);
    }
}
