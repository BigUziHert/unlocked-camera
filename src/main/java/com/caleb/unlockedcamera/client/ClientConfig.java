package com.caleb.unlockedcamera.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_UNLOCKED_CAMERA = BUILDER
            .comment("Enable the unlocked camera as a fourth view in the perspective cycle.")
            .define("enableUnlockedCamera", true);

    public static final ModConfigSpec.BooleanValue ENABLE_FREELOOK = BUILDER
            .comment("Enable freelook (hold the freelook key in first person to look around without turning).")
            .define("enableFreelook", true);

    public static final ModConfigSpec.BooleanValue FREELOOK_KEEP_DIRECTION = BUILDER
            .comment("When releasing the freelook key, turn the player to face where you were looking instead of easing the view back to where it was.")
            .define("freelookKeepDirection", false);

    public static final ModConfigSpec.BooleanValue CROSSHAIR_ALWAYS = BUILDER
            .comment("Always show the aim-corrected crosshair while the unlocked camera is active.")
            .define("crosshairAlways", false);

    public static final ModConfigSpec.BooleanValue CROSSHAIR_ON_SHOULDER_OFFSET = BUILDER
            .comment("Show the aim-corrected crosshair while the shoulder offset is engaged.")
            .define("crosshairOnShoulderOffset", true);

    public static final ModConfigSpec.BooleanValue SHOW_ENTER_MESSAGE = BUILDER
            .comment("Show the action-bar text when entering the unlocked camera.")
            .define("showEnterMessage", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SHOULDER_OFFSET = BUILDER
            .comment("Offset the camera over the shoulder when zoomed in close; press the swap-shoulder key to switch sides.")
            .define("enableShoulderOffset", true);

    public static final ModConfigSpec.DoubleValue SHOULDER_OFFSET_MAX_ZOOM = BUILDER
            .comment("Apply the shoulder offset when the camera is at or closer than this distance, in blocks.")
            .defineInRange("shoulderOffsetMaxZoom", 4.0, 1.0, 64.0);

    public static final ModConfigSpec.DoubleValue MIN_ZOOM = BUILDER
            .comment("Closest the unlocked camera can zoom in, in blocks.")
            .defineInRange("minZoom", 1.0, 0.5, 32.0);

    public static final ModConfigSpec.DoubleValue MAX_ZOOM = BUILDER
            .comment("Farthest the unlocked camera can zoom out, in blocks.")
            .defineInRange("maxZoom", 12.0, 4.0, 256.0);

    public static final ModConfigSpec.DoubleValue FREELOOK_YAW_LIMIT = BUILDER
            .comment("How far left/right freelook can swing, in degrees. 180 = look fully behind you.")
            .defineInRange("freelookYawLimit", 90.0, 10.0, 180.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    static boolean unlockedCameraEnabled() {
        return ENABLE_UNLOCKED_CAMERA.get();
    }

    static boolean freelookEnabled() {
        return ENABLE_FREELOOK.get();
    }

    static boolean freelookKeepDirection() {
        return FREELOOK_KEEP_DIRECTION.get();
    }

    static boolean crosshairAlways() {
        return CROSSHAIR_ALWAYS.get();
    }

    static boolean crosshairOnShoulderOffset() {
        return CROSSHAIR_ON_SHOULDER_OFFSET.get();
    }

    static boolean showEnterMessage() {
        return SHOW_ENTER_MESSAGE.get();
    }

    static boolean shoulderOffsetEnabled() {
        return ENABLE_SHOULDER_OFFSET.get();
    }

    static float shoulderOffsetMaxZoom() {
        return SHOULDER_OFFSET_MAX_ZOOM.get().floatValue();
    }

    static float minZoom() {
        return MIN_ZOOM.get().floatValue();
    }

    static float maxZoom() {
        // Guard against a hand-edited config where max ends up below min.
        return Math.max(MAX_ZOOM.get().floatValue(), minZoom());
    }

    static float freelookYawLimit() {
        return FREELOOK_YAW_LIMIT.get().floatValue();
    }
}
