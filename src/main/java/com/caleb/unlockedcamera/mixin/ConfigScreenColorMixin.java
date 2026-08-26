package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.UnlockedCameraMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Colors the ON/OFF text of boolean toggles on THIS mod's configuration screen:
 * lime green when enabled, red when disabled. Gated on the option's translation
 * key so every other mod's screen renders untouched.
 */
@Mixin(targets = "net.neoforged.neoforge.client.gui.ConfigurationScreen$ConfigurationSectionScreen")
public abstract class ConfigScreenColorMixin {
    @WrapOperation(method = "createBooleanValue", at = @At(value = "NEW",
            target = "net/minecraft/client/OptionInstance"))
    private OptionInstance<?> unlockedcamera$colorToggles(String caption,
            OptionInstance.TooltipSupplier<Boolean> tooltip,
            OptionInstance.CaptionBasedToString<Boolean> toString,
            OptionInstance.ValueSet<Boolean> values, Object initialValue,
            Consumer<Boolean> onValueUpdate, Operation<OptionInstance<?>> original) {
        if (caption.startsWith(UnlockedCameraMod.MOD_ID + ".")) {
            toString = (component, value) -> value
                    ? CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN)
                    : CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.RED);
        }
        return original.call(caption, tooltip, toString, values, initialValue, onValueUpdate);
    }
}
