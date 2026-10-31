package com.caleb.unlockedcamera.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional aim hold for Simply Swords' rotation-free ability packets. */
final class SimplySwordsCompat {
    private static boolean initialized;
    private static Method isAbilityStack;
    private static KeyMapping mainhandKey;
    private static KeyMapping offhandKey;
    private static Field mainhandPressed;
    private static Field offhandPressed;
    private static Field mainhandActiveStack;
    private static Field offhandActiveStack;

    private SimplySwordsCompat() {}

    /**
     * Abilities select targets from LivingEntity's head direction. The server
     * head follows a movement packet on its next tick, so a first-press-only
     * hold is too late. Pre-align while an ability weapon is equipped, just as
     * for crossbows; the caller still computes at most one aim per client tick.
     */
    static boolean isAbilityHeld(LocalPlayer player) {
        if (!initialized) init();
        if (isAbilityStack == null) return false;
        try {
            return isAbilityStack(player.getMainHandItem()) || isAbilityStack(player.getOffhandItem());
        } catch (ReflectiveOperationException | LinkageError ignored) {
            isAbilityStack = null;
            return false;
        }
    }

    /**
     * AbilityKeybindHandler sends from CLIENT_POST. Read its physical keys here
     * during Pre so the initial press reaches the server after the aim rotation.
     * Its previous pressed latch stays true until Post sends the release packet;
     * retaining the hold for that tick also covers charge/release abilities.
     * This narrower gate is used for vehicle riders, where an idle aim hold
     * would turn steerable mounts. Default-use channels are covered as well.
     */
    static boolean isAbilityEngaged(LocalPlayer player) {
        if (!initialized) {
            init();
        }
        if (isAbilityStack == null) {
            return false;
        }
        try {
            if (player.isUsingItem() && isAbilityStack(player.getUseItem())) {
                return true;
            }
            return handEngaged(player, InteractionHand.MAIN_HAND, mainhandKey,
                    mainhandPressed, mainhandActiveStack)
                    || handEngaged(player, InteractionHand.OFF_HAND, offhandKey,
                    offhandPressed, offhandActiveStack);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // An incompatible optional-mod update must not break ordinary input.
            isAbilityStack = null;
            return false;
        }
    }

    private static boolean handEngaged(LocalPlayer player, InteractionHand hand, KeyMapping key,
                                       Field pressed, Field activeStack) throws ReflectiveOperationException {
        // A menu causes the handler to release at Post even if the key is still
        // physically down. Its latch still covers that final release below.
        if (Minecraft.getInstance().screen == null && !key.isUnbound() && key.isDown()
                && isAbilityStack(player.getItemInHand(hand))) {
            return true;
        }
        return pressed.getBoolean(null) && isAbilityStack((ItemStack) activeStack.get(null));
    }

    private static boolean isAbilityStack(ItemStack stack) throws ReflectiveOperationException {
        return !stack.isEmpty() && Boolean.TRUE.equals(isAbilityStack.invoke(null, stack));
    }

    private static void init() {
        initialized = true;
        try {
            Class<?> handler = Class.forName("net.sweenus.simplyswords.client.AbilityKeybindHandler");
            mainhandKey = (KeyMapping) field(handler, "MAINHAND_ABILITY").get(null);
            offhandKey = (KeyMapping) field(handler, "OFFHAND_ABILITY").get(null);
            mainhandPressed = field(handler, "mainhandPressed");
            offhandPressed = field(handler, "offhandPressed");
            mainhandActiveStack = field(handler, "mainhandActiveStack");
            offhandActiveStack = field(handler, "offhandActiveStack");
            // Publish last, after the complete bridge resolved successfully.
            isAbilityStack = handler.getMethod("isAbilityStack", ItemStack.class);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            isAbilityStack = null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
