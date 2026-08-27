package com.caleb.unlockedcamera.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips mixins into optional mods' classes when that mod isn't installed, and
 * warns when one of those compat mixins applied to its target class but hooked
 * nothing — their injectors run with require = 0 (they're enhancement-only,
 * and a Create/Simulated refactor must not crash the launch), which would
 * otherwise fail silently.
 */
public class UnlockedCameraMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("unlockedcamera");

    private static boolean isCompatMixin(String mixinClassName) {
        return mixinClassName.contains(".Simulated") || mixinClassName.contains(".Create");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".Simulated")) {
            return LoadingModList.get().getModFileById("simulated") != null;
        }
        if (mixinClassName.contains(".Create")) {
            return LoadingModList.get().getModFileById("create") != null;
        }
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!isCompatMixin(mixinClassName)) {
            return;
        }
        // Every handler in the compat mixins is named unlockedcamera$...; a
        // successful injection leaves at least one call to such a handler in
        // the transformed class (Mixin's renaming keeps the original name as a
        // substring). None at all means every injector missed its target.
        for (MethodNode method : targetClass.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.contains("unlockedcamera$")) {
                    return;
                }
            }
        }
        LOGGER.warn("{} found nothing to hook in {} — that mod probably moved the code it targets; "
                + "the related crosshair compat is off until this mod is updated",
                mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1), targetClassName);
    }
}
