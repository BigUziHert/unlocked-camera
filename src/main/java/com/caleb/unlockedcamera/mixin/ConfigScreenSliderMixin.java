package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.UnlockedCameraMod;
import com.caleb.unlockedcamera.client.LinkedSliderRange;
import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.LinkedOptionSlider;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Renders THIS mod's ranged double options as sliders instead of number boxes
 * (NeoForge's configuration screen only does that for integers). The double is
 * mapped onto integer slider steps — fine steps for narrow ranges like the
 * shoulder amount, half blocks for zoom, whole degrees for wide angles. Gated
 * on the option's translation key, so other mods' screens are untouched.
 */
@Mixin(ConfigurationScreen.ConfigurationSectionScreen.class)
public abstract class ConfigScreenSliderMixin extends OptionsSubScreen {
    protected ConfigScreenSliderMixin(Screen lastScreen, Options options, net.minecraft.network.chat.Component title) {
        super(lastScreen, options, title);
    }

    /**
     * Plain arrow keys adjust the slider under the mouse — no click-to-focus
     * needed. A hovered slider wins over a focused one (point at what you
     * want); with nothing hovered, the focused widget gets the key through the
     * normal path below. Modified arrows (Ctrl/Alt/Shift+arrow) pass through.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modifiers == 0 && LinkedOptionSlider.hoverArrowKey(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * A drag can end with the mouse off its widget while the row is scrolled
     * out of view — nothing renders it, so its render-loop close never runs
     * and the gesture would stay open (a hole in the undo journal). Close open
     * gestures on every screen-level release, and when the screen goes away.
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        LinkedOptionSlider.closeOpenGestures();
        return handled;
    }

    @Override
    public void removed() {
        super.removed();
        LinkedOptionSlider.onScreenClosed();
    }

    /**
     * Hover-arrow freshness: the options list culls fully off-screen rows, so a
     * hovered slider that scrolls out of view stops rendering with its hovered
     * flag frozen true — it would keep receiving hover-arrow keys invisibly.
     * Clearing the target before the widgets render leaves it set only by
     * sliders that actually drew this frame. (An @Inject, not an override:
     * ConfigurationSectionScreen defines render itself.)
     */
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void unlockedcamera$frameStart(GuiGraphics guiGraphics, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        LinkedOptionSlider.onFrameStart();
    }

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
        java.util.function.IntFunction<Component> display =
                value -> Component.literal(unlockedcamera$format(value * step));
        // The zoom pair pushes each other; the shoulder threshold lives inside
        // the min..max zoom window — it stops at either bound when dragged and
        // follows when a bound moves through it, but never moves the bounds.
        // Linked sliders journal through Commit (one composite undo step per
        // gesture, covering the pushed sibling); their OptionInstance consumer
        // below only applies. (The threshold's own follow when a zoom bound
        // moves through it is bounded and not journaled.)
        // Pushes compare in RAW config units: on the step grid a hand-edited
        // maxZoom of 12.3 reads as 12.5, and a minZoom dragged to 12.5 would
        // stop pushing exactly there — leaving the pair inverted by the
        // rounding slack. The journal snapshots raw values for the same reason.
        java.util.function.IntSupplier minScaled =
                () -> (int) Math.round(ClientConfig.MIN_ZOOM.get() / step);
        java.util.function.IntSupplier maxScaled =
                () -> (int) Math.round(ClientConfig.MAX_ZOOM.get() / step);
        java.util.function.IntSupplier thresholdScaled =
                () -> (int) Math.round(ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get() / step);
        OptionInstance.ValueSet<Integer> valueSet = switch (key) {
            case "minZoom" -> {
                java.util.List<LinkedSliderRange.Linked> links = java.util.List.of(
                        new LinkedSliderRange.Linked("maxZoom", maxScaled,
                                ClientConfig.MAX_ZOOM::get, ClientConfig.MAX_ZOOM::set),
                        new LinkedSliderRange.Linked("shoulderOffsetMaxZoom", thresholdScaled,
                                ClientConfig.SHOULDER_OFFSET_MAX_ZOOM::get, ClientConfig.SHOULDER_OFFSET_MAX_ZOOM::set));
                yield new LinkedSliderRange(intRange, minScaled, ClientConfig.MIN_ZOOM::get,
                        () -> scaledMin, () -> scaledMax, links,
                        v -> {
                            double dragged = v * step;
                            if (dragged > ClientConfig.MAX_ZOOM.get()) {
                                ClientConfig.MAX_ZOOM.set(dragged);
                            }
                            if (dragged > ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get()) {
                                ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(dragged);
                            }
                        },
                        unlockedcamera$gestureCommit(key, target, links), display);
            }
            case "maxZoom" -> {
                java.util.List<LinkedSliderRange.Linked> links = java.util.List.of(
                        new LinkedSliderRange.Linked("minZoom", minScaled,
                                ClientConfig.MIN_ZOOM::get, ClientConfig.MIN_ZOOM::set),
                        new LinkedSliderRange.Linked("shoulderOffsetMaxZoom", thresholdScaled,
                                ClientConfig.SHOULDER_OFFSET_MAX_ZOOM::get, ClientConfig.SHOULDER_OFFSET_MAX_ZOOM::set));
                yield new LinkedSliderRange(intRange, maxScaled, ClientConfig.MAX_ZOOM::get,
                        () -> scaledMin, () -> scaledMax, links,
                        v -> {
                            double dragged = v * step;
                            if (dragged < ClientConfig.MIN_ZOOM.get()) {
                                ClientConfig.MIN_ZOOM.set(dragged);
                            }
                            if (dragged < ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get()) {
                                ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(dragged);
                            }
                        },
                        unlockedcamera$gestureCommit(key, target, links), display);
            }
            case "shoulderOffsetMaxZoom" -> {
                java.util.List<LinkedSliderRange.Linked> links = java.util.List.of();
                yield new LinkedSliderRange(intRange, thresholdScaled, ClientConfig.SHOULDER_OFFSET_MAX_ZOOM::get,
                        minScaled, maxScaled, links, v -> {},
                        unlockedcamera$gestureCommit(key, target, links), display);
            }
            // Unlinked sliders use the same widget too, so arrow stepping,
            // hover-targeted arrows, and apply-on-release behave uniformly.
            default -> new LinkedSliderRange(intRange,
                    () -> (int) Math.round(source.get() / step), source::get,
                    () -> scaledMin, () -> scaledMax,
                    java.util.List.of(), v -> {},
                    unlockedcamera$gestureCommit(key, target, java.util.List.of()), display);
        };
        cir.setReturnValue(new ConfigurationScreen.ConfigurationSectionScreen.Element(
                getTranslationComponent(key), getTooltipComponent(key, range),
                new OptionInstance<>(getTranslationKey(key), getTooltip(key, range),
                        (caption, value) -> Component.literal(unlockedcamera$format(value * step)),
                        valueSet, null,
                        (int) Math.round(source.get() / step),
                        newValue -> {
                            double newDouble = newValue * step;
                            if (newDouble != source.get()) {
                                // Journaled by the widget's Commit instead.
                                target.accept(newDouble);
                                onChanged(key);
                            }
                        })));
    }

    /** A Commit journaling a gesture as one composite undo step: the dragged
     * value plus every linked setting the gesture pushed, so a single Undo
     * reverts everything the gesture touched. Values arrive in raw config
     * units (the widget snapshots the config itself), so Undo restores an
     * off-grid value exactly — never its stepped neighbour. */
    private LinkedSliderRange.Commit unlockedcamera$gestureCommit(String key,
            java.util.function.Consumer<Double> own,
            java.util.List<LinkedSliderRange.Linked> links) {
        return (oldOwn, newOwn, linkedStarts, linkedNow) -> {
            java.util.List<ConfigurationScreen.UndoManager.Step<?>> steps = new java.util.ArrayList<>();
            steps.add(undoManager.step((Double v) -> {
                own.accept(v);
                onChanged(key);
            }, newOwn, (Double v) -> {
                own.accept(v);
                onChanged(key);
            }, oldOwn));
            for (int i = 0; i < links.size(); i++) {
                if (linkedStarts[i] != null) {
                    java.util.function.Consumer<Double> set = links.get(i).set();
                    String linkedKey = links.get(i).key();
                    steps.add(undoManager.step((Double v) -> {
                        set.accept(v);
                        onChanged(linkedKey);
                    }, linkedNow[i], (Double v) -> {
                        set.accept(v);
                        onChanged(linkedKey);
                    }, linkedStarts[i]));
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
