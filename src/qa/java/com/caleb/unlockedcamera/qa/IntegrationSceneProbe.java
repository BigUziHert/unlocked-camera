package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Multi-tick, integrated-server gameplay checks. Only compiled with -PcameraQa. */
@EventBusSubscriber(modid = "unlockedcamera", value = Dist.CLIENT)
public final class IntegrationSceneProbe {
    private static final Vec3 PLAYER = new Vec3(1710.5, 150, 190.5);
    private static final AABB AREA = new AABB(1695, 145, 175, 1730, 170, 225);
    private static final String TAG = "unlockedcamera_qa";
    private static final ArrayDeque<Step> steps = new ArrayDeque<>();
    private static Consumer<String> output;
    private static boolean running;
    private static int wait;
    private static BlockPos target;
    private static Vec3 cameraForward;
    private static volatile UUID victim;
    private static volatile boolean chestPicked, entityPicked;
    private static volatile Entity carriedOriginal;
    private static KeyMapping abilityKey;
    private static InputConstants.Key previousAbilityKey;
    private static boolean previousAbilityDown;
    private static boolean pendingAbilityPress;
    private static volatile Shot shot;
    private static volatile ServerSnapshot original;
    private static CameraType originalType;
    private static boolean originalToggle, originalShoulder;
    private static double originalAmount, originalMax;
    private static boolean originalActive;
    private static float originalDistance;

    private record Step(int delayAfter, Action action) {}
    @FunctionalInterface private interface Action { void run(Minecraft mc) throws Exception; }
    private record ServerSnapshot(Vec3 position, float yaw, float pitch, GameType mode,
                                  int selected, List<ItemStack> inventory) {}
    private static final class Shot {
        final String item;
        final Vec3 expected;
        final Set<UUID> seen = new HashSet<>();
        volatile double bestDot = -1;
        volatile int observed;
        Shot(String item, Vec3 expected) { this.item = item; this.expected = expected; }
    }

    private IntegrationSceneProbe() {}

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        // Simulate physical input between game ticks: Pre must observe the new
        // key before Simply Swords sends its ability packet during CLIENT_POST.
        if (running && pendingAbilityPress && abilityKey != null) {
            pendingAbilityPress = false;
            abilityKey.setDown(true);
            output.accept("SIMPLY_SWORDS_ABILITY INPUT applied between ticks");
        }
    }

    /** Start once; call tick(mc) from ClientTickEvent.Post until INTEGRATION DONE. */
    public static void start(Minecraft mc, Consumer<String> log) throws Exception {
        start(mc, log, false);
    }

    /** Small rerun: generic QA command `probe IntegrationSceneProbe`. */
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        start(mc, log, true);
    }

    private static void start(Minecraft mc, Consumer<String> log, boolean abilityOnly) throws Exception {
        if (running) throw new IllegalStateException("Integration probe already running");
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            throw new IllegalStateException("Integration probe requires an isolated singleplayer world");
        }
        output = log;
        original = null;
        originalType = mc.options.getCameraType();
        originalToggle = ClientConfig.TOGGLE_MOD.get();
        originalShoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        originalAmount = ClientConfig.SHOULDER_OFFSET_AMOUNT.get();
        originalMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        originalActive = field("active").getBoolean(null);
        originalDistance = field("targetDistance").getFloat(null);
        running = true;
        wait = 0;
        steps.clear();
        log.accept("INTEGRATION START temporary platform=" + PLAYER);
        add(50, m -> server(m, player -> {
            List<ItemStack> inventory = new ArrayList<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) inventory.add(player.getInventory().getItem(i).copy());
            original = new ServerSnapshot(player.position(), player.getYRot(), player.getXRot(),
                    player.gameMode.getGameModeForPlayer(), player.getInventory().selected, inventory);
            ServerLevel level = player.serverLevel();
            for (BlockPos p : BlockPos.betweenClosed(1704, 149, 184, 1717, 156, 205)) {
                level.setBlock(p, p.getY() == 149 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().clearContent();
            player.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
            player.setYRot(0); player.setXRot(0);
            player.getInventory().selected = 0;
            player.containerMenu.broadcastChanges();
        }));
        add(25, m -> {
            m.setScreen(null);
            m.player.getInventory().selected = 0;
            shoulder(m);
        });
        add(15, m -> {
            Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(m.player);
            cameraForward = UnlockedCameraClient.crosshairRayDirection(m.player);
            if (origin == null || cameraForward == null) throw new IllegalStateException("No shoulder ray");
            target = BlockPos.containing(origin.add(cameraForward.scale(2)));
            output.accept("INTEGRATION FIXTURE player=" + m.player.position() + " camera="
                    + m.gameRenderer.getMainCamera().getPosition() + " target=" + target);
        });
        if (abilityOnly) {
            addAbilityCheck();
            add(10, m -> finish(m, "DONE"));
            return;
        }
        for (String block : new String[] {"minecraft:stone", "minecraft:glass", "minecraft:oak_fence", "create:gearbox"}) {
            add(12, m -> server(m, p -> p.serverLevel().setBlock(target,
                    BuiltInRegistries.BLOCK.get(ResourceLocation.parse(block)).defaultBlockState(), 3)));
            add(2, m -> checkBlockAndPing(m, block));
        }
        addCarryChecks();
        for (String sword : new String[] {"minecraft:diamond_sword", "simplyswords:iron_rapier", "simplyswords:iron_chakram"}) addMeleeCheck(sword);
        add(20, IntegrationSceneProbe::shoulder);
        for (String weapon : new String[] {"minecraft:bow", "minecraft:crossbow", "aether:phoenix_bow",
                "deep_aether:storm_bow", "cataclysm:cursed_bow", "twilightforest:triple_bow"}) addProjectileCheck(weapon);
        addProjectileCheck("minecraft:snowball", "minecraft:air", 1);
        addProjectileCheck("minecraft:trident", "minecraft:air", 36);
        addProjectileCheck("simplyswords:iron_spear", "minecraft:air", 1);
        addProjectileCheck("create:potato_cannon", "minecraft:potato", 1);
        addProjectileCheck("supplementaries:slingshot", "minecraft:cobblestone", 36);
        addProjectileCheck("aether:golden_dart_shooter", "aether:golden_dart", 14);
        addProjectileCheck("the_bumblezone:stinger_spear", "minecraft:air", 36);
        addAbilityCheck();
        add(10, m -> finish(m, "DONE"));
    }

    public static void tick(Minecraft mc) {
        if (!running) return;
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            running = false;
            output.accept("INTEGRATION ABORT world closed");
            return;
        }
        try {
            // Hold the fixture's orientation steady through BTP and cosmetic bow follow.
            mc.player.setYRot(0); mc.player.yRotO = 0;
            mc.player.setXRot(0); mc.player.xRotO = 0;
            btpFacing();
            if (shot != null) {
                Shot current = shot;
                server(mc, player -> observeProjectiles(player, current));
            }
            if (wait-- > 0) return;
            Step step = steps.poll();
            if (step == null) { finish(mc, "DONE"); return; }
            step.action.run(mc);
            wait = step.delayAfter;
        } catch (Throwable e) {
            output.accept("INTEGRATION ERROR " + e);
            e.printStackTrace();
            try { finish(mc, "ABORT"); } catch (Exception nested) { output.accept("INTEGRATION RESTORE_ERROR " + nested); }
        }
    }

    private static void addCarryChecks() {
        add(15, m -> {
            equip(m, "minecraft:air");
            chestPicked = false;
            entityPicked = false;
            // CarryOn has a separate 2.5-block limit measured from player feet.
            // The ordinary pick fixture at +2, +1.5 is outside that limit.
            BlockPos previousTarget = target;
            target = target.north();
            setCarryKey(true);
            server(m, p -> {
                p.serverLevel().setBlock(previousTarget, Blocks.AIR.defaultBlockState(), 3);
                p.serverLevel().setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
            });
        });
        add(12, m -> {
            m.gameRenderer.pick(1);
            if (!(m.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(target)) {
                throw new IllegalStateException("Carry chest not under crosshair: " + m.hitResult);
            }
            output.accept("CARRYON PICKUP_REQUEST " + m.gameMode.useItemOn(m.player, InteractionHand.MAIN_HAND, hit));
        });
        add(10, m -> server(m, p -> {
            boolean carrying = carrying(p);
            boolean removed = p.serverLevel().getBlockState(target).isAir();
            chestPicked = carrying && removed;
            result("CARRYON chest pickup", chestPicked, "carrying=" + carrying + " removed=" + removed);
            p.serverLevel().setBlock(target.south(2), Blocks.STONE.defaultBlockState(), 3);
        }));
        add(12, m -> useCrosshairBlock(m, "CARRYON chest place"));
        add(10, m -> server(m, p -> {
            boolean placed = p.serverLevel().getBlockState(target.south()).is(Blocks.CHEST);
            result("CARRYON chest place", chestPicked && !carrying(p) && placed,
                    "pickupConfirmed=" + chestPicked + " carrying=" + carrying(p) + " placed=" + placed);
            clearCarry(p);
            p.serverLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 3);
            p.serverLevel().setBlock(target.south(), Blocks.AIR.defaultBlockState(), 3);
            p.serverLevel().setBlock(target.south(2), Blocks.AIR.defaultBlockState(), 3);
            spawnCow(p, new Vec3(target.getX() + .5, 150.35, target.getZ() + .5));
            carriedOriginal = p.serverLevel().getEntity(victim);
        }));
        add(12, m -> {
            m.gameRenderer.pick(1);
            if (m.hitResult instanceof EntityHitResult hit) {
                output.accept("CARRYON ENTITY_REQUEST " + m.gameMode.interact(m.player, hit.getEntity(), InteractionHand.MAIN_HAND));
            } else output.accept("CARRYON entity pickup FAIL no entity hit=" + m.hitResult);
        });
        add(10, m -> server(m, p -> {
            boolean removed = carriedOriginal != null && carriedOriginal.isRemoved()
                    && p.serverLevel().getEntity(victim) == null;
            entityPicked = carrying(p) && removed;
            result("CARRYON entity pickup", entityPicked, "carrying=" + carrying(p) + " originalRemoved=" + removed);
            p.serverLevel().setBlock(target.south(2), Blocks.STONE.defaultBlockState(), 3);
        }));
        add(12, m -> useCrosshairBlock(m, "CARRYON entity place"));
        add(10, m -> {
            setCarryKey(false);
            server(m, p -> {
                boolean placed = p.serverLevel().getEntitiesOfClass(Cow.class, AREA).stream()
                        .anyMatch(cow -> cow != carriedOriginal && cow.getTags().contains(TAG));
                result("CARRYON entity place", entityPicked && !carrying(p) && placed,
                        "pickupConfirmed=" + entityPicked + " carrying=" + carrying(p) + " newCowPresent=" + placed);
                clearCarry(p);
                cleanupEntities(p);
                p.serverLevel().setBlock(target.south(2), Blocks.AIR.defaultBlockState(), 3);
            });
        });
    }

    private static void addMeleeCheck(String item) {
        add(20, m -> {
            equip(m, item);
            m.options.setCameraType(CameraType.FIRST_PERSON);
            field("active").setBoolean(null, false);
            ((KeyMapping) field("FREELOOK_KEY").get(null)).setDown(true);
            field("freelookYaw").setFloat(null, 75);
            field("freelookPitch").setFloat(null, 0);
            server(m, p -> {
                cleanupEntities(p);
                Vec3 at = PLAYER.add(Vec3.directionFromRotation(0, 75).scale(2));
                spawnCow(p, new Vec3(at.x, PLAYER.y + .35, at.z));
            });
        });
        add(35, m -> {
            m.gameRenderer.pick(1);
            Method attack = Minecraft.class.getDeclaredMethod("startAttack");
            attack.setAccessible(true);
            output.accept("MELEE REQUEST item=" + item + " hit=" + m.hitResult + " result=" + attack.invoke(m));
        });
        add(10, m -> server(m, p -> {
            Entity entity = victim == null ? null : p.serverLevel().getEntity(victim);
            float health = entity instanceof Cow cow ? cow.getHealth() : Float.NaN;
            result("MELEE " + item, entity instanceof Cow && health > 0 && health < 100,
                    "victimPresent=" + (entity instanceof Cow) + " remainingHealth=" + health);
            cleanupEntities(p);
        }));
    }

    private static void addProjectileCheck(String item) {
        addProjectileCheck(item, "minecraft:arrow", 36);
    }

    /** Real Simply Swords key handler and packet; inspect its server-selected target. */
    private static void addAbilityCheck() {
        add(25, m -> {
            equip(m, "simplyswords:magispear", "minecraft:air");
            shoulder(m);
            Field keyField = Class.forName("net.sweenus.simplyswords.client.AbilityKeybindHandler")
                    .getDeclaredField("MAINHAND_ABILITY");
            keyField.setAccessible(true);
            abilityKey = (KeyMapping) keyField.get(null);
            previousAbilityKey = abilityKey.getKey();
            previousAbilityDown = abilityKey.isDown();
            abilityKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(86));
            abilityKey.setDown(false);
            Vec3 at = UnlockedCameraClient.crosshairRayGapFreeOrigin(m.player)
                    .add(UnlockedCameraClient.crosshairRayDirection(m.player).scale(3));
            server(m, p -> {
                try {
                    Class<?> awakening = Class.forName("net.sweenus.simplyswords.api.AwakeningApi");
                    ItemStack awakened = (ItemStack) awakening.getMethod("initializeFullyAwakened", ItemStack.class)
                            .invoke(null, p.getMainHandItem());
                    p.getInventory().setItem(0, awakened);
                    p.getCooldowns().removeCooldown(awakened.getItem());
                    Class.forName("net.sweenus.simplyswords.world.WeaponAbilityCooldownManager")
                            .getMethod("clearCooldown", LivingEntity.class, ItemStack.class).invoke(null, p, awakened);
                    p.containerMenu.broadcastChanges();
                    output.accept("SIMPLY_SWORDS_ABILITY fixture awakened="
                            + awakening.getMethod("isAbilityUnlocked", ItemStack.class).invoke(null, awakened));
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                cleanupEntities(p);
                p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
                p.setYRot(0); p.setXRot(0); p.setYHeadRot(0);
                // Slingshot block ammunition can place a block in front of the
                // projectile wall. Reset that test-only volume before targeting.
                for (BlockPos pos : BlockPos.betweenClosed(1704, 150, 191, 1717, 156, 194))
                    p.serverLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                Zombie zombie = EntityType.ZOMBIE.create(p.serverLevel());
                if (zombie == null) throw new IllegalStateException("Zombie creation failed");
                zombie.setPos(at.x, PLAYER.y + .1, at.z);
                zombie.setNoAi(true); zombie.setNoGravity(true); zombie.setPersistenceRequired(); zombie.addTag(TAG);
                zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
                zombie.setHealth(100);
                p.serverLevel().addFreshEntity(zombie);
                victim = zombie.getUUID();
                try {
                    boolean allowed = (boolean) Class.forName("net.sweenus.simplyswords.util.HelperMethods")
                            .getMethod("checkAbilityTarget", LivingEntity.class, LivingEntity.class).invoke(null, zombie, p);
                    output.accept("SIMPLY_SWORDS_ABILITY fixture target=" + victim + " type=minecraft:zombie eligible=" + allowed);
                    if (!allowed) throw new IllegalStateException("Configured ability rules reject zombie fixture");
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
        });
        add(8, m -> {
            m.gameRenderer.pick(1);
            if (!(m.hitResult instanceof EntityHitResult hit) || !hit.getEntity().getUUID().equals(victim))
                throw new IllegalStateException("Ability/entity-ping fixture is not the exact zombie under the crosshair: " + m.hitResult);
            PingActionProbe.start(m, output);
        });
        add(8, m -> {
            m.gameRenderer.pick(1);
            output.accept("SIMPLY_SWORDS_ABILITY PRESS item=simplyswords:magispear bodyYaw="
                    + m.player.getYRot() + " shoulder=.95 freelook=false hit=" + m.hitResult);
            pendingAbilityPress = true;
        });
        add(6, m -> {
            abilityKey.setDown(false);
            server(m, p -> {
                try {
                    Class<?> manager = Class.forName("net.sweenus.simplyswords.world.MagispearAbilityManager");
                    Field active = manager.getDeclaredField("ACTIVE"); active.setAccessible(true);
                    Map<?, ?> byLevel = (Map<?, ?>) active.get(null);
                    Map<?, ?> actors = (Map<?, ?>) byLevel.get(p.serverLevel());
                    Object ability = actors == null ? null : actors.get(p.getUUID());
                    Entity expected = victim == null ? null : p.serverLevel().getEntity(victim);
                    Vec3 center = null;
                    Entity selected = null;
                    Vec3 facing = null;
                    if (ability != null) {
                        Field centerField = ability.getClass().getDeclaredField("center"); centerField.setAccessible(true);
                        center = (Vec3) centerField.get(ability);
                        Field executionField = ability.getClass().getDeclaredField("execution"); executionField.setAccessible(true);
                        Object execution = executionField.get(ability);
                        Object context = execution.getClass().getMethod("context").invoke(execution);
                        selected = (Entity) context.getClass().getMethod("target").invoke(context);
                        facing = (Vec3) context.getClass().getMethod("facing").invoke(context);
                    }
                    double error = expected == null || center == null ? Double.POSITIVE_INFINITY
                            : Math.hypot(center.x - expected.getX(), center.z - expected.getZ());
                    boolean exactTarget = expected != null && selected != null && expected.getUUID().equals(selected.getUUID());
                    result("SIMPLY_SWORDS_ABILITY server target", ability != null && exactTarget && error < .001,
                            "active=" + (ability != null) + " center=" + center + " victim="
                                    + (expected == null ? null : expected.position()) + " horizontalError=" + error
                                    + " selectedUuid=" + (selected == null ? null : selected.getUUID())
                                    + " expectedUuid=" + victim + " exactTarget=" + exactTarget + " contextFacing=" + facing);
                    // End the real ability after observing targeting, before its rain/movement sequence.
                    if (ability != null) {
                        Method cancel = manager.getDeclaredMethod("cancel", ServerLevel.class, ability.getClass());
                        cancel.setAccessible(true); cancel.invoke(null, p.serverLevel(), ability);
                        actors.remove(p.getUUID());
                    }
                    cleanupEntities(p);
                    p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
        });
        add(5, m -> restoreAbilityKey());
    }

    private static void addProjectileCheck(String item, String ammo, int useTicks) {
        add(20, m -> {
            shot = null;
            equip(m, item, ammo);
            server(m, p -> {
                cleanupEntities(p);
                p.serverLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                // Wall provides a stable convergence target beyond ordinary interaction reach.
                for (BlockPos pos : BlockPos.betweenClosed(1704, 150, 195, 1717, 155, 195)) {
                    p.serverLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                }
            });
        });
        add(useTicks, m -> {
            if (m.player.getMainHandItem().isEmpty()) { output.accept("PROJECTILE SKIP missing item=" + item); return; }
            float[] aim = UnlockedCameraClient.crosshairAimAngles();
            if (aim == null) throw new IllegalStateException("Projectile aim missing for " + item);
            shot = new Shot(item, Vec3.directionFromRotation(aim[1], aim[0]));
            double bodyDot = shot.expected.normalize().dot(Vec3.directionFromRotation(m.player.getXRot(), m.player.getYRot()));
            if (bodyDot >= .995) throw new IllegalStateException("Projectile fixture cannot distinguish body aim: dot=" + bodyDot);
            output.accept("PROJECTILE USE item=" + item + " ammo=" + ammo + " duration=" + useTicks + " aim=" + aim[0] + "," + aim[1]
                    + " bodyDirectionDot=" + bodyDot
                    + " result=" + m.gameMode.useItem(m.player, InteractionHand.MAIN_HAND));
            m.options.keyUse.setDown(true);
        });
        add(5, m -> {
            m.options.keyUse.setDown(false);
            m.gameMode.releaseUsingItem(m.player);
        });
        add(15, m -> {
            if (item.equals("minecraft:crossbow")) {
                output.accept("PROJECTILE FIRE_CROSSBOW " + m.gameMode.useItem(m.player, InteractionHand.MAIN_HAND));
            }
        });
        add(5, m -> {
            Shot current = shot;
            if (current != null) result("PROJECTILE " + item, current.observed > 0 && current.bestDot > .995,
                    "observed=" + current.observed + " bestDirectionDot=" + current.bestDot);
            shot = null;
        });
    }

    private static void observeProjectiles(ServerPlayer player, Shot current) {
        for (Projectile projectile : player.serverLevel().getEntitiesOfClass(Projectile.class, AREA)) {
            if (projectile.getOwner() != player || current.seen.contains(projectile.getUUID())) continue;
            Vec3 velocity = projectile.getDeltaMovement();
            if (velocity.lengthSqr() < .01 || projectile.tickCount > 2) continue;
            current.seen.add(projectile.getUUID());
            projectile.addTag(TAG);
            double dot = velocity.normalize().dot(current.expected.normalize());
            current.bestDot = Math.max(current.bestDot, dot);
            current.observed++;
            output.accept("PROJECTILE SAMPLE item=" + current.item + " entity=" + BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType())
                    + " ticks=" + projectile.tickCount + " velocity=" + velocity + " directionDot=" + dot);
        }
    }

    private static void checkBlockAndPing(Minecraft mc, String block) throws Exception {
        mc.gameRenderer.pick(1);
        boolean picked = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
        result("BLOCK " + block, picked, "hit=" + mc.hitResult);
        HitResult ping = (HitResult) Class.forName("nx.pingwheel.common.math.Raycast")
                .getMethod("traceDirectional", Vec3.class, float.class, double.class, boolean.class)
                .invoke(null, UnlockedCameraClient.crosshairRayDirection(mc.player), 1f, 6d, true);
        result("PING_RAY " + block, ping instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target), "hit=" + ping);
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = (Vec3) Class.forName("com.simibubi.create.foundation.utility.RaycastHelper")
                .getMethod("getTraceTarget", net.minecraft.world.entity.player.Player.class, double.class, Vec3.class)
                .invoke(null, mc.player, 4.5d, eye);
        double dot = end.subtract(eye).normalize().dot(mc.hitResult.getLocation().subtract(eye).normalize());
        result("CREATE_RAY " + block, picked && dot > .99999, "targetDot=" + dot);
    }

    private static void useCrosshairBlock(Minecraft mc, String label) {
        mc.gameRenderer.pick(1);
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            output.accept(label + " REQUEST hit=" + hit.getBlockPos() + " face=" + hit.getDirection()
                    + " result=" + mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit));
        } else output.accept(label + " FAIL no block hit=" + mc.hitResult);
    }

    private static void shoulder(Minecraft mc) throws Exception {
        ((KeyMapping) field("FREELOOK_KEY").get(null)).setDown(false);
        field("freelookYaw").setFloat(null, 0);
        field("freelookPitch").setFloat(null, 0);
        ClientConfig.TOGGLE_MOD.set(true);
        ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        field("active").setBoolean(null, true);
        field("targetDistance").setFloat(null, 4);
        field("smoothedDistance").setFloat(null, 4);
        field("shoulderSide").setInt(null, 1);
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    private static void btpFacing() throws Exception {
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object zero = rotation.getField("ZERO").get(null);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, zero);
        Field playerRotation = camera.getClass().getDeclaredField("playerRotation");
        playerRotation.setAccessible(true);
        playerRotation.set(camera, zero);
    }

    private static void equip(Minecraft mc, String id) {
        equip(mc, id, "minecraft:arrow");
    }

    private static void equip(Minecraft mc, String id, String ammo) {
        mc.player.getInventory().selected = 0;
        server(mc, player -> {
            player.getInventory().setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))));
            player.getInventory().setItem(1, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(ammo)), 64));
            player.getInventory().offhand.set(0, ItemStack.EMPTY);
            player.getInventory().selected = 0;
            player.containerMenu.broadcastChanges();
        });
    }

    private static void spawnCow(ServerPlayer player, Vec3 pos) {
        Cow cow = EntityType.COW.create(player.serverLevel());
        if (cow == null) throw new IllegalStateException("Cow creation failed");
        cow.setPos(pos); cow.setNoAi(true); cow.setNoGravity(true); cow.setPersistenceRequired(); cow.addTag(TAG);
        cow.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
        cow.setHealth(100);
        player.serverLevel().addFreshEntity(cow);
        victim = cow.getUUID();
    }

    private static void cleanupEntities(ServerPlayer player) {
        for (Entity entity : player.serverLevel().getEntities((Entity) null, AREA, e -> e.getTags().contains(TAG))) entity.discard();
    }

    private static void setCarryKey(boolean down) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        KeyMapping key = (KeyMapping) Class.forName("tschipp.carryon.client.keybinds.CarryOnKeybinds").getField("carryKey").get(null);
        key.setDown(down);
        // The copied user's CarryOn binding is UNKNOWN, which delegates to sneaking.
        mc.options.keyShift.setDown(down);
        mc.player.input.shiftKeyDown = down;
        mc.player.setShiftKeyDown(down);
        Class.forName("tschipp.carryon.CarryOnCommonClient").getMethod("checkForKeybinds").invoke(null);
    }

    private static boolean carrying(ServerPlayer player) {
        try {
            Object data = carryData(player);
            return (boolean) data.getClass().getMethod("isCarrying").invoke(data);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Object carryData(ServerPlayer player) throws Exception {
        return Class.forName("tschipp.carryon.common.carry.CarryOnDataManager")
                .getMethod("getCarryData", net.minecraft.world.entity.player.Player.class).invoke(null, player);
    }

    private static void clearCarry(ServerPlayer player) {
        try {
            Object data = carryData(player);
            data.getClass().getMethod("clear").invoke(data);
            Class.forName("tschipp.carryon.common.carry.CarryOnDataManager")
                    .getMethod("setCarryData", net.minecraft.world.entity.player.Player.class, data.getClass()).invoke(null, player, data);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void finish(Minecraft mc, String status) throws Exception {
        running = false;
        steps.clear(); shot = null;
        mc.options.keyUse.setDown(false);
        mc.gameMode.releaseUsingItem(mc.player);
        ((KeyMapping) field("FREELOOK_KEY").get(null)).setDown(false);
        field("freelookYaw").setFloat(null, 0); field("freelookPitch").setFloat(null, 0);
        setCarryKey(false);
        restoreAbilityKey();
        ClientConfig.TOGGLE_MOD.set(originalToggle);
        ClientConfig.ENABLE_SHOULDER_OFFSET.set(originalShoulder);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(originalAmount);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(originalMax);
        field("active").setBoolean(null, originalActive);
        field("targetDistance").setFloat(null, originalDistance);
        mc.options.setCameraType(originalType);
        server(mc, player -> {
            clearCarry(player); cleanupEntities(player);
            ServerSnapshot snapshot = original;
            if (snapshot != null) {
                for (int i = 0; i < snapshot.inventory.size(); i++) player.getInventory().setItem(i, snapshot.inventory.get(i));
                player.getInventory().selected = snapshot.selected;
                player.setGameMode(snapshot.mode);
                player.teleportTo(snapshot.position.x, snapshot.position.y, snapshot.position.z);
                player.setYRot(snapshot.yaw); player.setXRot(snapshot.pitch);
                player.containerMenu.broadcastChanges();
            }
        });
        output.accept("INTEGRATION " + status);
    }

    private static void restoreAbilityKey() {
        pendingAbilityPress = false;
        if (abilityKey == null) return;
        abilityKey.setDown(previousAbilityDown);
        abilityKey.setKey(previousAbilityKey);
        abilityKey = null;
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) throw new IllegalStateException("Test player absent from integrated server");
                action.accept(player);
            } catch (Throwable e) { output.accept("INTEGRATION SERVER_ERROR " + e); e.printStackTrace(); }
        });
    }

    private static Field field(String name) throws Exception {
        Field field = UnlockedCameraClient.class.getDeclaredField(name);
        field.setAccessible(true); return field;
    }
    private static void add(int waitAfter, Action action) { steps.add(new Step(waitAfter, action)); }
    private static void result(String test, boolean pass, String detail) {
        output.accept(test + " " + (pass ? "PASS " : "FAIL ") + detail);
    }
}
