package com.caleb.contraptioncamera.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue MIN_ZOOM = BUILDER
            .comment("Closest the contraption camera can zoom in, in blocks.")
            .defineInRange("minZoom", 1.0, 0.5, 32.0);

    public static final ModConfigSpec.DoubleValue MAX_ZOOM = BUILDER
            .comment("Farthest the contraption camera can zoom out, in blocks.")
            .defineInRange("maxZoom", 12.0, 4.0, 256.0);

    public static final ModConfigSpec.DoubleValue FREELOOK_YAW_LIMIT = BUILDER
            .comment("How far left/right freelook can swing, in degrees. 180 = look fully behind you.")
            .defineInRange("freelookYawLimit", 90.0, 10.0, 180.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
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
