package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/** Public mod lifecycle/readiness APIs used only by the disposable item-use fixtures. */
public final class SimplySwordsQaIsolation {
    private static final String PREFIX = "net.sweenus.simplyswords.world.";
    private static final List<Method> gates = new ArrayList<>();
    private static final List<Method> worldClears = new ArrayList<>();
    private static boolean initialized, present;
    private SimplySwordsQaIsolation() {}

    public static void initialize(Minecraft mc, Path evidence) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Ability fixture isolation is limited to the disposable test level");
        if (!initialized) {
            gates.clear(); worldClears.clear();
            try { Class.forName(PREFIX + "WeaponAbilityCooldownManager"); present = true; }
            catch (ClassNotFoundException absent) { initialized = true; return; }
            Path jar;
            try (var files = Files.list(mc.gameDirectory.toPath().resolve("mods"))) {
                jar = files.filter(path -> path.getFileName().toString().startsWith("simplyswords-")
                        && path.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
            }
            try (JarFile file = new JarFile(jar.toFile())) {
                for (String name : file.stream().map(entry -> entry.getName())
                        .filter(name -> name.startsWith("net/sweenus/simplyswords/world/") && name.endsWith("Manager.class"))
                        .sorted().toList()) {
                    Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'));
                    for (String gate : new String[] {"hasActive", "hasScheduled", "hasPendingCloneStrikes"}) {
                        try { gates.add(type.getMethod(gate, ServerLevel.class)); }
                        catch (NoSuchMethodException absent) { /* no public readiness API */ }
                    }
                    try { worldClears.add(type.getMethod("clear", ServerLevel.class)); }
                    catch (NoSuchMethodException absent) { /* natural expiry is used instead */ }
                }
            }
            gates.sort(Comparator.comparing(Method::toString));
            initialized = true;
        }
        StringBuilder text = new StringBuilder("QA only: public lifecycle APIs; no private manager-map mutations.\n")
                .append("World-scoped clear APIs apply only to build/modpack-test/game's disposable integrated-server level.\n")
                .append("Uncleared active managers must expire while the test player remains unequipped.\n\nReadiness gates:\n");
        for (Method method : gates) text.append(method.toGenericString()).append('\n');
        text.append("\nAvailable world clear APIs (called only while that manager reports active):\n");
        for (Method method : worldClears) text.append(method.toGenericString()).append('\n');
        text.append("\nPlayer APIs used between cases:\nPlayerWeaponAbilityManager.stop(player, hand)\n")
                .append("PlayerWeaponAbilityChannelManager.stop(player, hand)\nShadowstingShadowDanceManager.end(player)\n")
                .append("MagibladeAbilityManager.cancelCharging(player, false)\nMoltenEdgeAbilityManager.resetWielder(player)\n")
                .append("ThunderbrandAbilityManager.cancelCharging(player)\nWhisperwindVisualManager.finishDash(level, player)\n")
                .append("WeaponAbilityCooldownManager.clearCooldown(player, testedStack)\n");
        Files.writeString(evidence.resolve("ability-isolation-methods.txt"), text);
    }

    public static List<String> active(ServerPlayer player) {
        List<String> result = new ArrayList<>();
        if (!present) return result;
        try {
            for (Method method : gates) if ((boolean) method.invoke(null, player.serverLevel()))
                result.add(method.getDeclaringClass().getSimpleName() + "." + method.getName());
            return result;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Ability readiness API failed", error); }
    }

    public static void cleanup(ServerPlayer player, Consumer<String> log) {
        if (!present) return;
        try {
            List<String> before = active(player);
            for (InteractionHand hand : InteractionHand.values()) {
                call("PlayerWeaponAbilityManager", "stop", new Class<?>[] {ServerPlayer.class, InteractionHand.class}, player, hand);
                call("PlayerWeaponAbilityChannelManager", "stop", new Class<?>[] {ServerPlayer.class, InteractionHand.class}, player, hand);
            }
            call("ShadowstingShadowDanceManager", "end", new Class<?>[] {ServerPlayer.class}, player);
            call("MagibladeAbilityManager", "cancelCharging", new Class<?>[] {LivingEntity.class, boolean.class}, player, false);
            call("MoltenEdgeAbilityManager", "resetWielder", new Class<?>[] {LivingEntity.class}, player);
            call("ThunderbrandAbilityManager", "cancelCharging", new Class<?>[] {LivingEntity.class}, player);
            call("WhisperwindVisualManager", "finishDash", new Class<?>[] {ServerLevel.class, LivingEntity.class}, player.serverLevel(), player);
            List<String> called = new ArrayList<>();
            for (Method clear : worldClears) {
                boolean relevant = before.stream().anyMatch(name -> name.startsWith(clear.getDeclaringClass().getSimpleName() + "."));
                if (relevant) { clear.invoke(null, player.serverLevel()); called.add(clear.getDeclaringClass().getSimpleName() + ".clear(ServerLevel)"); }
            }
            if (!before.isEmpty()) log.accept("ITEM_AIR_USE ABILITY_ISOLATION activeBefore=" + before + " publicWorldClears=" + called);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Ability cleanup API failed", error); }
    }

    public static void clearCooldown(ServerPlayer player, ItemStack stack) {
        if (!present || stack.isEmpty()) return;
        try { call("WeaponAbilityCooldownManager", "clearCooldown", new Class<?>[] {LivingEntity.class, ItemStack.class}, player, stack); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Ability cooldown API failed", error); }
    }

    private static Object call(String type, String method, Class<?>[] parameters, Object... arguments) throws ReflectiveOperationException {
        return Class.forName(PREFIX + type).getMethod(method, parameters).invoke(null, arguments);
    }
}
