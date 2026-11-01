package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.util.function.Consumer;

/** Keeps an already-started priority-test draw alive; never initiates an item use. */
public final class SableAddonDrawGuardProbe {
    private static boolean registered, running, savedUse;
    private static Field parentRunning, parentIndex, parentPhase;
    private static Consumer<String> log;
    private SableAddonDrawGuardProbe() {}
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        parentRunning = field("running"); parentIndex = field("index"); parentPhase = field("phase");
        if (running || mc.player == null || !parentRunning.getBoolean(null) || parentIndex.getInt(null) < 20)
            throw new IllegalStateException("Active Sable addon draw-priority fixture required");
        savedUse = mc.options.keyUse.isDown(); log = output; running = true;
        if (!registered) { NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SableAddonDrawGuardProbe::tick); registered = true; }
        log.accept("SABLE_ADDON_DRAW_GUARD START savedUse=" + savedUse + " scope=already-started-offhand-draw-only");
        update(mc);
    }
    private static void tick(ClientTickEvent.Pre ignored) {
        if (!running) return;
        try { update(Minecraft.getInstance()); }
        catch (Exception error) {
            Minecraft.getInstance().options.keyUse.setDown(savedUse); running = false;
            log.accept("SABLE_ADDON_DRAW_GUARD ERROR " + error);
        }
    }
    private static void update(Minecraft mc) throws Exception {
        if (!parentRunning.getBoolean(null)) {
            mc.options.keyUse.setDown(savedUse); running = false;
            log.accept("SABLE_ADDON_DRAW_GUARD RESTORED keyUse=" + savedUse); return;
        }
        int index = parentIndex.getInt(null), phase = parentPhase.getInt(null);
        boolean drawing = (index == 20 || index == 21) && (phase == 3 || phase == 4)
                && mc.player != null && mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND;
        mc.options.keyUse.setDown(drawing);
    }
    private static Field field(String name) throws Exception {
        Field f = SableAddonInteractionProbe.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
