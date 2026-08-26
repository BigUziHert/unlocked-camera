package com.caleb.unlockedcamera.client;

import com.mojang.serialization.Codec;
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
        IntConsumer onDragValue, IntFunction<Component> display)
        implements OptionInstance.SliderableValueSet<Integer> {

    @Override
    public Optional<Integer> validateValue(Integer value) {
        return delegate.validateValue(
                Mth.clamp(value, liveMin.getAsInt(), liveMax.getAsInt()));
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
