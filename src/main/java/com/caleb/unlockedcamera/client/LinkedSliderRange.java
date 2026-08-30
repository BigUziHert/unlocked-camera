package com.caleb.unlockedcamera.client;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * A slider range linked to sibling options. The knob is pinned live inside
 * {@code liveMin}..{@code liveMax} (so a threshold can stop at another option's
 * current value), every dragged value flows through {@code onDragValue} (so a
 * bound can push its dependents along), and the idle slider follows pushes by
 * re-syncing from {@code ownValue}. Used to keep the zoom sliders and the
 * shoulder threshold working together.
 */
public record LinkedSliderRange(OptionInstance.IntRange delegate,
        IntSupplier ownValue, IntSupplier liveMin, IntSupplier liveMax,
        List<Linked> linked, IntConsumer onDragValue, Commit commit,
        IntFunction<Component> display)
        implements OptionInstance.SliderableValueSet<Integer> {

    /**
     * A setting this slider's drags can push along: its config key (so the
     * screen's onChanged bookkeeping names the setting that actually moved),
     * its live value in slider units, and its setter in config units (used by
     * {@link Commit} to journal the push into the undo history).
     */
    public record Linked(String key, IntSupplier value, Consumer<Double> set) {}

    /**
     * Journals a finished gesture into the config screen's undo history: the
     * dragged slider's change plus every linked setting the gesture pushed —
     * as one composite step, so a single Undo reverts the whole gesture.
     * {@code linkedStarts[i]} is null when linked setting i was not pushed.
     */
    @FunctionalInterface
    public interface Commit {
        void commit(int oldOwn, int newOwn, Integer[] linkedStarts, int[] linkedNow);
    }

    @Override
    public Optional<Integer> validateValue(Integer value) {
        // Order and bound the fences before clamping: a hand-inverted zoom pair
        // in the TOML (minZoom > maxZoom) flips liveMin above liveMax, and a raw
        // clamp then collapses every input to liveMax — the row lies about the
        // stored value and a bare click commits the rewrite. pinKnob already
        // orders its bounds the same way.
        int lo = Math.min(liveMin.getAsInt(), liveMax.getAsInt());
        int hi = Math.max(liveMin.getAsInt(), liveMax.getAsInt());
        lo = Mth.clamp(lo, delegate.minInclusive(), delegate.maxInclusive());
        hi = Mth.clamp(hi, delegate.minInclusive(), delegate.maxInclusive());
        return delegate.validateValue(Mth.clamp(value, lo, hi));
    }

    @Override
    public double toSliderValue(Integer value) {
        return delegate.toSliderValue(value);
    }

    @Override
    public Integer fromSliderValue(double value) {
        return delegate.fromSliderValue(value);
    }

    @Override
    public Codec<Integer> codec() {
        return delegate.codec();
    }

    @Override
    public Function<OptionInstance<Integer>, AbstractWidget> createButton(
            OptionInstance.TooltipSupplier<Integer> tooltip, Options options,
            int x, int y, int width, Consumer<Integer> onValueChanged) {
        return instance -> new LinkedOptionSlider(options, x, y, width, 20,
                instance, this, tooltip, onValueChanged);
    }
}
