package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.UnlockedCameraMod;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Renders THIS mod's ranged double options as sliders instead of number boxes
 * (NeoForge's configuration screen only does that for integers). The double is
 * mapped onto integer slider steps — fine steps for narrow ranges like the
 * shoulder amount, half blocks for zoom, whole degrees for wide angles. Gated
 * on the option's translation key, so other mods' screens are untouched.
 */
@Mixin(ConfigurationScreen.ConfigurationSectionScreen.class)
public abstract class ConfigScreenSliderMixin {
    @Shadow(remap = false)
    @Final
    protected ConfigurationScreen.UndoManager undoManager;

    @Shadow(remap = false)
    protected abstract String getTranslationKey(String key);

    @Shadow(remap = false)
    protected abstract MutableComponent getTranslationComponent(String key);

    @Shadow(remap = false)
    protected abstract Component getTooltipComponent(String key, ModConfigSpec.Range<?> range);

    @Shadow(remap = false)
    protected abstract <T> OptionInstance.TooltipSupplier<T> getTooltip(String key, ModConfigSpec.Range<?> range);

    @Shadow(remap = false)
    protected abstract void onChanged(String key);

    @Inject(method = "createDoubleValue", at = @At("HEAD"), cancellable = true, remap = false)
    private void unlockedcamera$doubleSliders(String key, ModConfigSpec.ValueSpec spec,
            Supplier<Double> source, Consumer<Double> target,
            CallbackInfoReturnable<ConfigurationScreen.ConfigurationSectionScreen.Element> cir) {
        if (!getTranslationKey(key).startsWith(UnlockedCameraMod.MOD_ID + ".")) {
            return;
        }
        ModConfigSpec.Range<Double> range = spec.getRange();
        if (range == null) {
            return;
        }
        double span = range.getMax() - range.getMin();
        double step = span <= 2.0 ? 0.05 : (span <= 64.0 ? 0.5 : 1.0);
        int scaledMin = (int) Math.round(range.getMin() / step);
        int scaledMax = (int) Math.round(range.getMax() / step);
        cir.setReturnValue(new ConfigurationScreen.ConfigurationSectionScreen.Element(
                getTranslationComponent(key), getTooltipComponent(key, range),
                new OptionInstance<>(getTranslationKey(key), getTooltip(key, range),
                        (caption, value) -> Component.literal(unlockedcamera$format(value * step)),
                        new OptionInstance.IntRange(scaledMin, scaledMax), null,
                        (int) Math.round(source.get() / step),
                        newValue -> {
                            double newDouble = newValue * step;
                            if (newDouble != source.get()) {
                                undoManager.add(v -> {
                                    target.accept(v);
                                    onChanged(key);
                                }, newDouble, v -> {
                                    target.accept(v);
                                    onChanged(key);
                                }, source.get());
                            }
                        })));
    }

    private static String unlockedcamera$format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
