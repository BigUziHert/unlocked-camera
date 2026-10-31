package com.caleb.unlockedcamera.qa;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.settings.KeyModifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Exercises the installed Simply Swords handler's real key/latch state without sending ability packets. */
public final class SimplySwordsAimProbe {
    private SimplySwordsAimProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws ReflectiveOperationException {
        if (!mc.isSameThread() || mc.player == null || mc.screen != null || mc.player.isUsingItem()) {
            throw new IllegalStateException("Simply Swords probe needs an idle player in the isolated test world");
        }
        Class<?> handler = Class.forName("net.sweenus.simplyswords.client.AbilityKeybindHandler");
        KeyMapping mainKey = (KeyMapping) field(handler, "MAINHAND_ABILITY").get(null);
        KeyMapping offKey = (KeyMapping) field(handler, "OFFHAND_ABILITY").get(null);
        Field mainPressed = field(handler, "mainhandPressed");
        Field offPressed = field(handler, "offhandPressed");
        Field mainActive = field(handler, "mainhandActiveStack");
        Field offActive = field(handler, "offhandActiveStack");
        Method engaged = Class.forName("com.caleb.unlockedcamera.client.SimplySwordsCompat")
                .getDeclaredMethod("isAbilityEngaged", LocalPlayer.class);
        engaged.setAccessible(true);
        ItemStack ability = new ItemStack(BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("simplyswords", "magiblade")));
        ItemStack offhandAbility = new ItemStack(BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("simplyswords", "livyatan")));
        if (ability.isEmpty() || offhandAbility.isEmpty()) {
            throw new IllegalStateException("Installed Simply Swords lacks the expected Magiblade/Livyatan test items");
        }

        InputConstants.Key previousMainKey = mainKey.getKey();
        InputConstants.Key previousOffKey = offKey.getKey();
        KeyModifier previousMainModifier = mainKey.getKeyModifier();
        KeyModifier previousOffModifier = offKey.getKeyModifier();
        boolean previousMainDown = mainKey.isDown();
        boolean previousOffDown = offKey.isDown();
        boolean previousMainPressed = mainPressed.getBoolean(null);
        boolean previousOffPressed = offPressed.getBoolean(null);
        Object previousMainActive = mainActive.get(null);
        Object previousOffActive = offActive.get(null);
        ItemStack previousMain = mc.player.getMainHandItem();
        // Better Combat intentionally hides a two-handed offhand stack from the
        // equipment getter. Preserve the actual inventory slot for restoration.
        ItemStack previousOff = mc.player.getInventory().offhand.get(0);
        try {
            mainKey.setKeyModifierAndCode(KeyModifier.NONE, InputConstants.Type.KEYSYM.getOrCreate(86));
            offKey.setKeyModifierAndCode(KeyModifier.NONE, InputConstants.Type.KEYSYM.getOrCreate(66));
            mainKey.setDown(false);
            offKey.setDown(false);
            mainPressed.setBoolean(null, false);
            offPressed.setBoolean(null, false);
            mainActive.set(null, ItemStack.EMPTY);
            offActive.set(null, ItemStack.EMPTY);
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, ability.copy());
            mc.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            check(engaged, mc, false, "idle unique sword does not engage the mounted ability gate", log);

            mainKey.setDown(true);
            check(engaged, mc, true, "first main-hand press is covered before CLIENT_POST", log);
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
            check(engaged, mc, false, "non-ability item ignores the ability key", log);

            mc.player.setItemInHand(InteractionHand.MAIN_HAND, ability.copy());
            mainKey.setKey(InputConstants.UNKNOWN);
            check(engaged, mc, false, "unbound key does not start a hold", log);
            mainKey.setDown(false);
            mainPressed.setBoolean(null, true);
            mainActive.set(null, ability.copy());
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            check(engaged, mc, true, "release tick retains remembered ability aim", log);

            mainPressed.setBoolean(null, false);
            mainActive.set(null, ItemStack.EMPTY);
            check(engaged, mc, false, "completed release drops the hold", log);
            mc.player.setItemInHand(InteractionHand.OFF_HAND, ability.copy());
            offKey.setDown(true);
            check(engaged, mc, false, "Better Combat's two-handed offhand restriction is respected", log);
            mc.player.setItemInHand(InteractionHand.OFF_HAND, offhandAbility.copy());
            check(engaged, mc, true, "offhand ability press is covered", log);
            offKey.setDown(false);
            offPressed.setBoolean(null, true);
            offActive.set(null, offhandAbility.copy());
            check(engaged, mc, true, "offhand ability release is covered", log);
            log.accept("SIMPLY_SWORDS_AIM PASS 9 eligibility checks (packet/impact testing is separate)");
        } finally {
            mainKey.setKeyModifierAndCode(previousMainModifier, previousMainKey);
            offKey.setKeyModifierAndCode(previousOffModifier, previousOffKey);
            mainKey.setDown(previousMainDown);
            offKey.setDown(previousOffDown);
            mainPressed.setBoolean(null, previousMainPressed);
            offPressed.setBoolean(null, previousOffPressed);
            mainActive.set(null, previousMainActive);
            offActive.set(null, previousOffActive);
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, previousMain);
            mc.player.setItemInHand(InteractionHand.OFF_HAND, previousOff);
        }
    }

    private static void check(Method engaged, Minecraft mc, boolean expected, String scenario, Consumer<String> log)
            throws ReflectiveOperationException {
        boolean actual = (boolean) engaged.invoke(null, mc.player);
        if (actual != expected) {
            Class<?> handler = Class.forName("net.sweenus.simplyswords.client.AbilityKeybindHandler");
            Method abilityCheck = handler.getMethod("isAbilityStack", ItemStack.class);
            for (InteractionHand hand : InteractionHand.values()) {
                KeyMapping key = (KeyMapping) field(handler,
                        hand == InteractionHand.MAIN_HAND ? "MAINHAND_ABILITY" : "OFFHAND_ABILITY").get(null);
                ItemStack held = mc.player.getItemInHand(hand);
                log.accept("SIMPLY_SWORDS_DIAGNOSTIC hand=" + hand
                        + ",item=" + BuiltInRegistries.ITEM.getKey(held.getItem()) + ",count=" + held.getCount()
                        + ",ability=" + abilityCheck.invoke(null, held) + ",key=" + key.getKey().getName()
                        + ",down=" + key.isDown() + ",rawDown=" + field(KeyMapping.class, "isDown").getBoolean(key)
                        + ",unbound=" + key.isUnbound() + ",modifier=" + key.getKeyModifier()
                        + ",context=" + key.getKeyConflictContext());
            }
            log.accept("SIMPLY_SWORDS_DIAGNOSTIC screen=" + mc.screen + ",bridgeResolved="
                    + (field(Class.forName("com.caleb.unlockedcamera.client.SimplySwordsCompat"),
                            "isAbilityStack").get(null) != null));
            throw new IllegalStateException("SIMPLY_SWORDS_AIM FAIL " + scenario + ": expected="
                    + expected + ",actual=" + actual);
        }
        log.accept("SIMPLY_SWORDS_AIM PASS " + scenario);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
