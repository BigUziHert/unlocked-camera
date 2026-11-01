package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Real ability-key packet and server-selected target under held first-person freelook. */
public final class FreelookAbilityProbe {
    private FreelookAbilityProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        WeaponModeRunner.start(mc, log, "freelookAbility");
    }
}
