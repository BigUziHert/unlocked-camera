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

/**
 * A slider range linked to a sibling option: dragging past the sibling's value
 * pushes the sibling along instead of crossing it, and the idle slider follows
 * the pushes live. Used to keep min and max zoom working together.
 */
public record LinkedSliderRange(OptionInstance.IntRange delegate,
        IntSupplier ownValue, IntSupplier siblingValue, IntConsumer pushSibling,
        boolean pushesUp, IntFunction<Component> display)
        implements OptionInstance.SliderableValueSet<Integer> {

    @Override
    public Optional<Integer> validateValue(Integer value) {
        return delegate.validateValue(value);
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
