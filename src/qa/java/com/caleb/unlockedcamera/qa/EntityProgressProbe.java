package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Read-only diagnostics for long default-entity cases. */
public final class EntityProgressProbe {
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        StringBuilder line = new StringBuilder("ENTITY_PROGRESS");
        for (String name : new String[]{"running", "index", "mode", "phase", "age", "setupIssue", "targetId"})
            line.append(' ').append(name).append('=').append(get(name));
        CompletableFuture<?> task = (CompletableFuture<?>) get("task");
        Entity target = (Entity) get("target");
        line.append(" taskDone=").append(task != null && task.isDone());
        if (target != null) line.append(" target=").append(target.getType()).append(" at=").append(target.position())
                .append(" removed=").append(target.isRemoved()).append(" box=").append(target.getBoundingBox());
        line.append(" clientAt=").append(mc.player.position()).append(" screen=")
                .append(mc.screen == null ? "none" : mc.screen.getClass().getName());
        log.accept(line.toString());
    }
    private static Object get(String name) throws Exception {
        Field field = EntityActionProbe.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
}
