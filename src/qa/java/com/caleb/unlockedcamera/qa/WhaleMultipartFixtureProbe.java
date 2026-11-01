package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Public lifecycle cleanup of an already removed test-fixture parent only. */
public final class WhaleMultipartFixtureProbe {
    private WhaleMultipartFixtureProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Object game = mc;
        java.io.File directory = (java.io.File) game.getClass().getField("gameDirectory").get(game);
        if (!directory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Multipart fixture cleanup requires the disposable primary copy");
        Object hit = game.getClass().getField("hitResult").get(game);
        if (hit == null || !hit.getClass().getName().equals("net.minecraft.world.phys.EntityHitResult")) {
            log.accept("EGG_WHALE_FIXTURE NO_ENTITY_BLOCKER hit=" + hit); return;
        }
        Object part = hit.getClass().getMethod("getEntity").invoke(hit);
        Class<?> partType = Class.forName("com.crispytwig.naturalist.world.entity.MobPart");
        if (!partType.isInstance(part)) {
            log.accept("EGG_WHALE_FIXTURE OTHER_BLOCKER class=" + part.getClass().getName()); return;
        }
        Object parent = partType.getMethod("getParent").invoke(part);
        boolean removed = (boolean) parent.getClass().getMethod("isRemoved").invoke(parent);
        Object level = parent.getClass().getMethod("level").invoke(parent);
        Object clientLevel = game.getClass().getField("level").get(game);
        Object uuid = parent.getClass().getMethod("getUUID").invoke(parent);
        Object pos = parent.getClass().getMethod("position").invoke(parent);
        log.accept("EGG_WHALE_FIXTURE OBSERVED partClass=" + part.getClass().getName()
                + " parentClass=" + parent.getClass().getName() + " parentUuid=" + uuid
                + " parentRemoved=" + removed + " clientLevel=" + (level == clientLevel) + " position=" + pos);
        if (!removed || level != clientLevel || !parent.getClass().getName().equals(
                "com.crispytwig.naturalist.world.entity.animal.whale.Whale")) {
            log.accept("EGG_WHALE_FIXTURE UNCHANGED parent_not_exact_removed_whale"); return;
        }
        double x = (double) parent.getClass().getMethod("getX").invoke(parent);
        double y = (double) parent.getClass().getMethod("getY").invoke(parent);
        double z = (double) parent.getClass().getMethod("getZ").invoke(parent);
        if (Math.abs(x - 1708.5) > 16 || Math.abs(y - 170) > 16 || Math.abs(z - 192.5) > 16)
            throw new IllegalStateException("Removed whale lies outside the exact egg test arena");
        Object parts = parent.getClass().getMethod("getParts").invoke(parent);
        Class<?> multipartLevel = Class.forName("com.crispytwig.naturalist.world.level.MultipartLevel");
        partType.getMethod("unregisterAll", multipartLevel, parts.getClass()).invoke(null, level, parts);
        log.accept("EGG_WHALE_FIXTURE CLEARED parentUuid=" + uuid + " parts=" + Array.getLength(parts)
                + " method=public_MobPart.unregisterAll;already_removed_client_parent_only;server_unchanged");
    }
}
