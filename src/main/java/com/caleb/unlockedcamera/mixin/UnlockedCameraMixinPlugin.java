package com.caleb.unlockedcamera.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips mixins into optional mods' classes when that mod isn't installed.
 *
 * <p>The Create/Simulated compat injectors run with require = 0: they are
 * enhancement-only, and a Create/Simulated refactor must not crash the launch.
 * A miss is deliberately silent — a bytecode check here cannot see MixinExtras'
 * call-site rewiring (it happens after postApply), so any "hooked nothing"
 * warning at this phase false-alarms on every @WrapOperation mixin. If a mod
 * update moves a hooked call site, the symptom is simply that the related
 * crosshair compat stops applying, which play-testing after updates catches.
 */
public class UnlockedCameraMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".Simulated")) {
            return LoadingModList.get().getModFileById("simulated") != null;
        }
        if (mixinClassName.contains(".Create")) {
            return LoadingModList.get().getModFileById("create") != null;
        }
        if (mixinClassName.contains(".Tacz")) {
            return LoadingModList.get().getModFileById("tacz") != null;
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
    }
}
