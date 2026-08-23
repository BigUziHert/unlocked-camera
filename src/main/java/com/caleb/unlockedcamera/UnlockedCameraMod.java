package com.caleb.unlockedcamera;

import net.neoforged.fml.common.Mod;

/**
 * Common entry point. All of the actual functionality lives in
 * {@link com.caleb.unlockedcamera.client.UnlockedCameraClient}, which only
 * loads on the physical client — this mod does nothing on a dedicated server.
 */
@Mod(UnlockedCameraMod.MOD_ID)
public class UnlockedCameraMod {
    public static final String MOD_ID = "unlockedcamera";
}
