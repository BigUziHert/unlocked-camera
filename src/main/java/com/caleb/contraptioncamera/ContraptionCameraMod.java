package com.caleb.contraptioncamera;

import net.neoforged.fml.common.Mod;

/**
 * Common entry point. All of the actual functionality lives in
 * {@link com.caleb.contraptioncamera.client.ContraptionCameraClient}, which only
 * loads on the physical client — this mod does nothing on a dedicated server.
 */
@Mod(ContraptionCameraMod.MOD_ID)
public class ContraptionCameraMod {
    public static final String MOD_ID = "contraptioncamera";
}
