package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.UnlockedCameraMod;
import com.caleb.unlockedcamera.client.LinkedSliderRange;
import com.caleb.unlockedcamera.client.ClientConfig;
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
        OptionInstance.IntRange intRange = new OptionInstance.IntRange(scaledMin, scaledMax);
        // The two zoom sliders stop at each other's live value: min can rise to
        // meet max and max can drop to meet min, but they can never cross.
        java.util.function.IntFunction<Component> display =
                value -> Component.literal(unlockedcamera$format(value * step));
        // The zoom pair pushes each other; the shoulder threshold lives inside
        // the min..max zoom window — it stops at either bound when dragged and
        // follows when a bound moves through it, but never moves the bounds.
        // Linked sliders journal through Commit (one composite undo step per
        // gesture, covering the pushed sibling); their OptionInstance consumer
        // below only applies. (The threshold's own follow when a zoom bound
        // moves through it is bounded and not journaled.)
        java.util.function.IntSupplier minScaled =
                () -> (int) Math.round(ClientConfig.MIN_ZOOM.get() / step);
        java.util.function.IntSupplier maxScaled =
                () -> (int) Math.round(ClientConfig.MAX_ZOOM.get() / step);
        java.util.function.IntSupplier thresholdScaled =
                () -> (int) Math.round(ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get() / step);
        OptionInstance.ValueSet<Integer> valueSet = switch (key) {
            case "minZoom" -> {
                java.util.List<LinkedSliderRange.Linked> links = java.util.List.of(
                        new LinkedSliderRange.Linked("maxZoom", maxScaled, ClientConfig.MAX_ZOOM::set),
                        new LinkedSliderRange.Linked("shoulderOffsetMaxZoom", thresholdScaled, ClientConfig.SHOULDER_OFFSET_MAX_ZOOM::set));
                yield new LinkedSliderRange(intRange, minScaled,
                        () -> scaledMin, () -> scaledMax, links,
                        v -> {
                            if (v > maxScaled.getAsInt()) {
                                ClientConfig.MAX_ZOOM.set(v * step);
                            }
                            if (v > thresholdScaled.getAsInt()) {
                                ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(v * step);
                            }
                        },
                        unlockedcamera$gestureCommit(key, target, links, step), display);
            }
            case "maxZoom" -> {
                java.util.List<LinkedSliderRange.Linked> links = java.util.List.of(
                        new LinkedSliderRange.Linked("minZoom", minScaled, ClientConfig.MIN_ZOOM::set),
                        new LinkedSliderRange.Linked("shoulderOffsetMaxZoom", thresholdScaled, ClientConfig.SHOULDER_OFFSET_MAX_ZOOM::set));
                yield new LinkedSliderRange(intRange, maxScaled,
                        () -> scaledMin, () -> scaledMax, links,
                        v -> {
                            if (v < minScaled.getAsInt()) {
                                ClientConfig.MIN_ZOOM.set(v * step);
                            }
                            if (v < thresholdScaled.getAsInt()) {
                                ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(v * step);
                            }
                        },
                        unlockedcamera$gestureCommit(key, target, links, step), display);
            }
            case "shoulderOffsetMaxZoom" -> {
                java.util.List<LinkedSliderRange.Linked> links = java.util.List.of();
                yield new LinkedSliderRange(intRange, thresholdScaled,
                        minScaled, maxScaled, links, v -> {},
                        unlockedcamera$gestureCommit(key, target, links, step), display);
            }
            default -> intRange;
        };
        boolean linkedJournal = valueSet instanceof LinkedSliderRange;
        cir.setReturnValue(new ConfigurationScreen.ConfigurationSectionScreen.Element(
                getTranslationComponent(key), getTooltipComponent(key, range),
                new OptionInstance<>(getTranslationKey(key), getTooltip(key, range),
                        (caption, value) -> Component.literal(unlockedcamera$format(value * step)),
                        valueSet, null,
                        (int) Math.round(source.get() / step),
                        newValue -> {
                            double newDouble = newValue * step;
                            if (newDouble != source.get()) {
                                if (linkedJournal) {
                                    // Journaled by the widget's Commit instead.
                                    target.accept(newDouble);
                                    onChanged(key);
                                } else {
                                    undoManager.add(v -> {
                                        target.accept(v);
                                        onChanged(key);
                                    }, newDouble, v -> {
                                        target.accept(v);
                                        onChanged(key);
                                    }, source.get());
                                }
                            }
                        })));
    }

    /** A Commit journaling a gesture as one composite undo step: the dragged
     * value plus every linked setting the gesture pushed, so a single Undo
     * reverts everything the gesture touched. */
    private LinkedSliderRange.Commit unlockedcamera$gestureCommit(String key,
            java.util.function.Consumer<Double> own,
            java.util.List<LinkedSliderRange.Linked> links, double step) {
        return (oldOwn, newOwn, linkedStarts, linkedNow) -> {
            java.util.List<ConfigurationScreen.UndoManager.Step<?>> steps = new java.util.ArrayList<>();
            steps.add(undoManager.step((Double v) -> {
                own.accept(v);
                onChanged(key);
            }, newOwn * step, (Double v) -> {
                own.accept(v);
                onChanged(key);
            }, oldOwn * step));
            for (int i = 0; i < links.size(); i++) {
                if (linkedStarts[i] != null) {
                    java.util.function.Consumer<Double> set = links.get(i).set();
                    String linkedKey = links.get(i).key();
                    steps.add(undoManager.step((Double v) -> {
                        set.accept(v);
                        onChanged(linkedKey);
                    }, linkedNow[i] * step, (Double v) -> {
                        set.accept(v);
                        onChanged(linkedKey);
                    }, linkedStarts[i] * step));
                }
            }
            undoManager.add(steps);
        };
    }

    private static String unlockedcamera$format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
