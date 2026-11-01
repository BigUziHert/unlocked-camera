package com.caleb.unlockedcamera.qa;

import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ProjectileItem;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Actual loaded registry census. Inventory is deliberately never reported as a play-test pass. */
public final class RegistryCensusProbe {
    private static final Set<String> ITEM_ACTIONS = Set.of("use", "useOn", "onItemUseFirst",
            "releaseUsing", "finishUsingItem", "onUseTick", "interactLivingEntity", "hurtEnemy",
            "mineBlock", "onLeftClickEntity", "onEntitySwing", "inventoryTick", "getUseAnimation",
            "getUseDuration", "onArmorTick");
    private static final Set<String> BLOCK_ACTIONS = Set.of("useItemOn", "useWithoutItem", "attack",
            "entityInside", "stepOn", "getMenuProvider", "getAnalogOutputSignal", "getShape",
            "getInteractionShape", "getCollisionShape", "getStateForPlacement", "onWrenched",
            "onSneakWrenched");

    private RegistryCensusProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.isSameThread() || mc.level == null || mc.player == null) {
            throw new IllegalStateException("Census requires an active client world");
        }
        Path root = Path.of("qa", "census");
        Files.createDirectories(root);
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        var items = new ArrayList<Map<String, Object>>();
        var blocks = new ArrayList<Map<String, Object>>();
        var entities = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Integer>> counts = new TreeMap<>();
        Map<Class<?>, List<String>> itemMethods = new LinkedHashMap<>();
        Map<Class<?>, List<String>> blockMethods = new LinkedHashMap<>();
        int errors = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            var row = row(id.toString(), item.getClass());
            count(counts, id.getNamespace(), "items");
            row.put("blockItem", item instanceof BlockItem);
            row.put("projectileWeapon", item instanceof ProjectileWeaponItem);
            row.put("projectileItem", item instanceof ProjectileItem);
            row.put("actionMethods", itemMethods.computeIfAbsent(item.getClass(), c -> methods(c, ITEM_ACTIONS)));
            try {
                ItemStack stack = item.getDefaultInstance();
                row.put("defaultUseAnimation", stack.getUseAnimation().name());
                row.put("defaultFoodComponent", stack.has(DataComponents.FOOD));
                row.put("defaultMaxStackSize", stack.getMaxStackSize());
                row.put("defaultMaxDamage", stack.getMaxDamage());
                row.put("displayName", stack.getHoverName().getString());
            } catch (Throwable e) {
                errors++;
                row.put("inspectionError", e.toString());
            }
            items.add(row);
        }
        long stateCount = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            var row = row(id.toString(), block.getClass());
            count(counts, id.getNamespace(), "blocks");
            int n = block.getStateDefinition().getPossibleStates().size();
            stateCount += n;
            row.put("stateCount", n);
            row.put("defaultState", block.defaultBlockState().toString());
            row.put("hasBlockEntity", block.defaultBlockState().hasBlockEntity());
            row.put("actionMethods", blockMethods.computeIfAbsent(block.getClass(), c -> methods(c, BLOCK_ACTIONS)));
            blocks.add(row);
        }
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            var row = row(id.toString(), type.getClass());
            count(counts, id.getNamespace(), "entityTypes");
            row.put("category", type.getCategory().name());
            row.put("width", type.getWidth());
            row.put("height", type.getHeight());
            row.put("note", "Entity factory not invoked; registration does not establish interactability");
            entities.add(row);
        }
        var mods = new ArrayList<Map<String, Object>>();
        for (var mod : ModList.get().getMods()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", mod.getModId());
            row.put("name", mod.getDisplayName());
            row.put("version", mod.getVersion().toString());
            mods.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", Instant.now().toString());
        summary.put("scope", "All actual loaded items, blocks and entity types. Enumeration is not gameplay validation; item NBT/component variants and contextual interactions require separate fixtures.");
        summary.put("items", items.size());
        summary.put("blocks", blocks.size());
        summary.put("blockStates", stateCount);
        summary.put("entityTypes", entities.size());
        summary.put("mods", mods.size());
        summary.put("inspectionErrors", errors);
        summary.put("namespaceCounts", counts);
        Files.writeString(root.resolve("items.json"), gson.toJson(items));
        Files.writeString(root.resolve("blocks.json"), gson.toJson(blocks));
        Files.writeString(root.resolve("entities.json"), gson.toJson(entities));
        Files.writeString(root.resolve("mods.json"), gson.toJson(mods));
        Files.writeString(root.resolve("summary.json"), gson.toJson(summary));
        log.accept("CENSUS COMPLETE items=" + items.size() + " blocks=" + blocks.size()
                + " blockStates=" + stateCount + " entityTypes=" + entities.size()
                + " mods=" + mods.size() + " inspectionErrors=" + errors
                + " gameplayStatus=UNTESTED output=" + root.toAbsolutePath());
    }

    private static Map<String, Object> row(String id, Class<?> type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("class", type.getName());
        row.put("gameplayStatus", "UNTESTED");
        return row;
    }

    private static void count(Map<String, Map<String, Integer>> counts, String namespace, String kind) {
        counts.computeIfAbsent(namespace, n -> new TreeMap<>()).merge(kind, 1, Integer::sum);
    }

    private static List<String> methods(Class<?> type, Set<String> names) {
        // Retain exact signatures: same-named overloads can have different behavior.
        // The first declaration encountered is the inherited implementation owner.
        Map<String, String> owners = new TreeMap<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (names.contains(method.getName()) && !method.isSynthetic()) {
                    String signature = method.getName() + Arrays.toString(method.getParameterTypes());
                    owners.putIfAbsent(signature, c.getName() + "#" + signature);
                }
            }
        }
        // Public default interface methods (notably Create's IWrenchable) are
        // executable interaction implementations even without a class override.
        for (Method method : type.getMethods()) {
            if (names.contains(method.getName()) && !method.isSynthetic()) {
                String signature = method.getName() + Arrays.toString(method.getParameterTypes());
                owners.putIfAbsent(signature, method.getDeclaringClass().getName() + "#" + signature);
            }
        }
        return List.copyOf(owners.values());
    }
}
