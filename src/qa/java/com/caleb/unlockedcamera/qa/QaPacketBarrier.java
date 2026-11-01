package com.caleb.unlockedcamera.qa;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Ordered server observations after real client packets; QA-only, no gameplay bypass. */
public final class QaPacketBarrier {
    private static final String COMMAND = "unlockedcamera_qa_packet_barrier";
    private static final Set<MinecraftServer> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger TOKENS = new AtomicInteger();
    private record Pending(UUID player, Consumer<ServerPlayer> action, CompletableFuture<Void> future) {}
    private QaPacketBarrier() {}

    /** Call on the server thread as part of the awaited fixture setup. */
    public static synchronized void install(MinecraftServer server) {
        if (!INSTALLED.add(server)) return;
        server.getCommands().getDispatcher().register(Commands.literal(COMMAND)
                .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                    int token = IntegerArgumentType.getInteger(context, "token");
                    Pending pending = PENDING.remove(token);
                    if (pending == null || pending.future.isDone()) return 1;
                    try {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        if (!pending.player.equals(player.getUUID())) throw new IllegalStateException("QA packet barrier sender mismatch");
                        pending.action.accept(player); pending.future.complete(null);
                    } catch (Throwable error) { pending.future.completeExceptionally(error); }
                    return 1;
                })));
    }

    public static CompletableFuture<Void> send(Minecraft mc, Consumer<ServerPlayer> action) {
        int token = TOKENS.incrementAndGet(); CompletableFuture<Void> future = new CompletableFuture<>();
        Pending pending = new Pending(mc.player.getUUID(), action, future); PENDING.put(token, pending);
        future.whenComplete((result, error) -> PENDING.remove(token, pending));
        mc.player.connection.sendCommand(COMMAND + " " + token);
        return future;
    }
}
