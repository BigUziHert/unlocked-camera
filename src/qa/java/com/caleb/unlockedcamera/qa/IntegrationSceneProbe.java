package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.DiggerItem;
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
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.minecraft.world.damagesource.DamageTypes;

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
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.IntegerArgumentType;

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
    private static String baselineItem;
    private static final String ACK_COMMAND = "unlockedcamera_qa_weapon_ack";
    private static CompletableFuture<Void> serverTask = CompletableFuture.completedFuture(null);
    private static CompletableFuture<Void> observationTask = CompletableFuture.completedFuture(null);
    private static volatile int acknowledged;
    private static volatile boolean acknowledgedTeleport;
    private static int nextToken, pendingToken;
    private static boolean ackNeeded;
    private static int waitUntilServerTick, barrierAge;
    private static Method cameraPosition, cameraRotation;
    private static boolean standardRecheckMode;
    private static String deferredFinishStatus;
    private static boolean meleeListenersRegistered;
    private static volatile MeleeTrace meleeTrace;
    private static Set<String> completedOffsetMelee = Set.of();
    private static boolean offsetMeleeResuming;
    private static boolean meleePathDiagnostic;
    private static boolean abilityPairMode, abilityFirstPersonMode;
    private static String abilityView = "existing";

    /** Exact server victim/actor evidence, independently of startAttack's local return. */
    private static final class MeleeTrace {
        final Cow target;
        final UUID actor;
        final String item;
        volatile boolean requested, preflightDone, pristine, contaminated;
        volatile float preAttackHealth = Float.NaN, healthAtFirstAttack = Float.NaN;
        volatile int attackEvents, canceledEvents, damageBeforeRequest, unattributedDamageEvents, attributedDamageEvents, attributedCustomDamageEvents;
        volatile int clientAttackEvents, clickEvents, canceledClickEvents;
        volatile long lastAttackTick = Long.MIN_VALUE;
        volatile float attributedDamage, attributedCustomDamage, unattributedDamage;
        volatile Vec3 impactVelocity = Vec3.ZERO;
        final Set<String> damageSources = new java.util.concurrent.ConcurrentSkipListSet<>();
        MeleeTrace(Cow target, UUID actor, String item) { this.target = target; this.actor = actor; this.item = item; }
    }

    public static void meleeAttackStarted(AttackEntityEvent event) {
        MeleeTrace current = meleeTrace;
        if (running && meleePathDiagnostic && current != null && current.requested && event.getEntity().level().isClientSide
                && event.getEntity().getUUID().equals(current.actor)) {
            current.clientAttackEvents++;
            output.accept("MELEE CLIENT_ATTACK item=" + current.item + " picked=" + describeEntity(event.getTarget())
                    + " exactVictim=" + event.getTarget().getUUID().equals(current.target.getUUID()));
        }
        if (!running || current == null || event.getEntity().level().isClientSide || event.getTarget() != current.target
                || !event.getEntity().getUUID().equals(current.actor)) return;
        if (!current.requested) { current.contaminated = true; return; }
        if (current.attackEvents == 0) {
            current.healthAtFirstAttack = current.target.getHealth();
            if (current.healthAtFirstAttack != 100 || current.damageBeforeRequest > 0) current.contaminated = true;
        }
        current.attackEvents++;
        current.lastAttackTick = event.getEntity().level().getGameTime();
    }

    public static void meleeClickStarted(InputEvent.InteractionKeyMappingTriggered event) {
        MeleeTrace current = meleeTrace;
        if (!running || !meleePathDiagnostic || current == null || !current.requested || !event.isAttack()) return;
        current.clickEvents++;
        output.accept("MELEE INPUT_BEGIN item=" + current.item + " canceled=" + event.isCanceled()
                + " hit=" + describeHit(Minecraft.getInstance().hitResult));
    }

    public static void meleeClickObserved(InputEvent.InteractionKeyMappingTriggered event) {
        MeleeTrace current = meleeTrace;
        if (!running || !meleePathDiagnostic || current == null || !current.requested || !event.isAttack()) return;
        if (event.isCanceled()) current.canceledClickEvents++;
        output.accept("MELEE INPUT_END item=" + current.item + " canceled=" + event.isCanceled()
                + " shouldSwing=" + event.shouldSwingHand() + " hit=" + describeHit(Minecraft.getInstance().hitResult));
    }

    public static void meleeAttackObserved(AttackEntityEvent event) {
        MeleeTrace current = meleeTrace;
        if (!running || current == null || !current.requested || event.getEntity().level().isClientSide
                || event.getTarget() != current.target || !event.getEntity().getUUID().equals(current.actor)) return;
        if (event.isCanceled()) current.canceledEvents++;
        current.impactVelocity = current.target.getDeltaMovement();
    }

    public static void meleeDamageObserved(LivingDamageEvent.Post event) {
        MeleeTrace current = meleeTrace;
        if (!running || current == null || event.getEntity().level().isClientSide || event.getEntity() != current.target
                || event.getNewDamage() <= 0) return;
        var source = event.getSource();
        String sourceId = source.typeHolder().unwrapKey().map(key -> key.location().toString()).orElse(source.getMsgId());
        current.damageSources.add(sourceId);
        boolean exactAttackActor = current.requested && current.attackEvents > 0
                && current.lastAttackTick == event.getEntity().level().getGameTime()
                && source.getEntity() instanceof ServerPlayer player && player.getUUID().equals(current.actor)
                && source.getDirectEntity() == player;
        boolean directPlayerAttack = exactAttackActor && source.is(DamageTypes.PLAYER_ATTACK);
        // Installed Twilight Forest CustomDamageSwordItem intentionally replaces
        // player_attack for stale bread. Keep this narrow exception measurable
        // and preserve exact actor, direct actor, victim and attack-tick checks.
        boolean exactStaleBreadAttack = exactAttackActor && current.item.equals("twilightforest:stale_bread")
                && sourceId.equals("twilightforest:stale_sandwich");
        if (directPlayerAttack) { current.attributedDamageEvents++; current.attributedDamage += event.getNewDamage(); }
        else if (exactStaleBreadAttack) { current.attributedCustomDamageEvents++; current.attributedCustomDamage += event.getNewDamage(); }
        else {
            current.unattributedDamageEvents++; current.unattributedDamage += event.getNewDamage();
            if (!current.requested) current.damageBeforeRequest++;
            if (current.attackEvents == 0) current.contaminated = true;
        }
    }

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

    private static boolean offsetMeleeMode, freelookProjectileMode;
    private static boolean savedFreelookEnabled, savedKeepDirection, savedFreelookKey;
    private static float savedFreelookYaw, savedFreelookPitch;

    public static void offsetMelee(Minecraft mc, Consumer<String> log) throws Exception {
        offsetMeleeMode = true;
        completedOffsetMelee = Set.of(); offsetMeleeResuming = false;
        start(mc, log, "melee");
    }

    /** Resume interrupted offset coverage, retaining only each item's latest PASS. */
    public static void offsetMeleeResume(Minecraft mc, Consumer<String> log) throws Exception {
        java.nio.file.Path results = mc.gameDirectory.toPath().resolve("qa/results.log");
        Map<String, String> latest = new java.util.LinkedHashMap<>();
        var pattern = java.util.regex.Pattern.compile("^MELEE_OFFSET (\\S+) (PASS|FAIL|INCONCLUSIVE)\\b.*");
        for (String line : java.nio.file.Files.readAllLines(results)) {
            var match = pattern.matcher(line);
            if (match.matches()) latest.put(match.group(1), match.group(2));
        }
        Set<String> passed = new HashSet<>();
        latest.forEach((id, status) -> { if (status.equals("PASS")) passed.add(id); });
        completedOffsetMelee = Set.copyOf(passed); offsetMeleeResuming = true; offsetMeleeMode = true;
        log.accept("MELEE RESUME source=" + results + " latestRecorded=" + latest.size()
                + " preservedLatestPass=" + passed.size() + " policy=retry_missing_fail_inconclusive;prior_evidence_retained");
        start(mc, log, "melee");
    }

    public static void meleePathDiagnostic(Minecraft mc, Consumer<String> log) throws Exception {
        // A distinct evidence namespace preserves the failed broad sweep. This
        // schedules only its latest failures/limits and never rewrites those rows.
        Map<String, String> latest = new java.util.LinkedHashMap<>();
        var pattern = java.util.regex.Pattern.compile("^MELEE_OFFSET (\\S+) (PASS|FAIL|INCONCLUSIVE)\\b.*");
        for (String line : java.nio.file.Files.readAllLines(mc.gameDirectory.toPath().resolve("qa/results.log"))) {
            var match = pattern.matcher(line);
            if (match.matches()) latest.put(match.group(1), match.group(2));
        }
        Set<String> selected = new HashSet<>();
        latest.forEach((id, status) -> { if (!status.equals("PASS")) selected.add(id); });
        completedOffsetMelee = Set.copyOf(selected);
        meleePathDiagnostic = true; offsetMeleeMode = true;
        log.accept("MELEE DIAGNOSTIC_SELECTION latestNonPass=" + selected.size() + " ids=" + selected);
        start(mc, log, "melee-diagnostic");
    }

    public static void freelookProjectiles(Minecraft mc, Consumer<String> log) throws Exception {
        freelookProjectileMode = true;
        start(mc, log, "projectiles");
    }

    public static void freelookAbility(Minecraft mc, Consumer<String> log) throws Exception {
        freelookProjectileMode = true;
        start(mc, log, "ability");
    }

    public static void pairedAbilityRecheck(Minecraft mc, Consumer<String> log) throws Exception {
        abilityPairMode = true;
        standardRecheckMode = true;
        start(mc, log, "ability-paired");
    }

    public static void standardRecheck(Minecraft mc, Consumer<String> log) throws Exception {
        standardRecheckMode = true;
        start(mc, log, "standard");
    }

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

    @SubscribeEvent
    public static void projectileSpawn(EntityJoinLevelEvent event) {
        Shot current = shot;
        if (current == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof Projectile projectile)
                || !(projectile.getOwner() instanceof ServerPlayer owner)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && owner.getUUID().equals(mc.player.getUUID())) sampleProjectile(projectile, current);
    }

    /** Start once; call tick(mc) from ClientTickEvent.Post until INTEGRATION DONE. */
    public static void start(Minecraft mc, Consumer<String> log) throws Exception {
        start(mc, log, "standard");
    }

    /** Small rerun: generic QA command `probe IntegrationSceneProbe`. */
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        start(mc, log, "ability");
    }

    public static void projectiles(Minecraft mc, Consumer<String> log) throws Exception {
        start(mc, log, "projectiles");
    }

    public static void melee(Minecraft mc, Consumer<String> log) throws Exception {
        start(mc, log, "melee");
    }

    public static void projectileBaseline(Minecraft mc, Consumer<String> log, String item) throws Exception {
        if (running) throw new IllegalStateException("Integration probe already running");
        baselineItem = item;
        start(mc, log, "projectilebaseline");
    }

    private static void start(Minecraft mc, Consumer<String> log, String suite) throws Exception {
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
        savedFreelookEnabled = ClientConfig.ENABLE_FREELOOK.get();
        savedKeepDirection = ClientConfig.FREELOOK_KEEP_DIRECTION.get();
        savedFreelookKey = ((KeyMapping) field("FREELOOK_KEY").get(null)).isDown();
        savedFreelookYaw = field("freelookYaw").getFloat(null); savedFreelookPitch = field("freelookPitch").getFloat(null);
        if (offsetMeleeMode || freelookProjectileMode || standardRecheckMode) {
            ClientConfig.ENABLE_FREELOOK.set(true); ClientConfig.FREELOOK_KEEP_DIRECTION.set(false);
        }
        cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        cameraPosition.setAccessible(true); cameraRotation.setAccessible(true);
        serverTask = CompletableFuture.completedFuture(null); observationTask = CompletableFuture.completedFuture(null);
        nextToken = pendingToken = acknowledged = 0;
        ackNeeded = false; waitUntilServerTick = -1; barrierAge = 0;
        deferredFinishStatus = null;
        running = true;
        wait = 0;
        steps.clear();
        log.accept("INTEGRATION START suite=" + suite + " temporary platform=" + PLAYER);
        add(50, m -> server(m, player -> {
            m.getSingleplayerServer().getCommands().getDispatcher().register(Commands.literal(ACK_COMMAND)
                    .then(Commands.argument("token", IntegerArgumentType.integer()).executes(context -> {
                        ServerPlayer source = context.getSource().getPlayerOrException();
                        if (running && source.getUUID().equals(player.getUUID())) {
                            acknowledgedTeleport = teleportReady(source) && !nestedInteraction();
                            acknowledged = IntegerArgumentType.getInteger(context, "token");
                        }
                        return 1;
                    })));
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
            player.stopUsingItem(); player.containerMenu.broadcastFullState();
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
            if (m.player.position().distanceToSqr(PLAYER) > .04
                    || m.gameRenderer.getMainCamera().getPosition().distanceTo(m.player.getEyePosition()) > 5
                    || target.getCenter().distanceTo(m.player.getEyePosition()) > 5)
                throw new IllegalStateException("Invalid integration camera/player/target fixture");
            output.accept("INTEGRATION FIXTURE player=" + m.player.position() + " camera="
                    + m.gameRenderer.getMainCamera().getPosition() + " target=" + target
                    + " pose=direct_fixture;server_tasks_and_packet_acks_serialized;full_inventory_sync=true");
        });
        if (suite.equals("ability")) {
            addAbilityCheck();
            add(10, m -> finish(m, "DONE"));
            return;
        }
        if (suite.equals("ability-paired")) {
            addAbilityCheck("first_person");
            addAbilityCheck("freelook");
            add(10, m -> finish(m, "DONE"));
            return;
        }
        if (suite.equals("projectiles") || suite.equals("projectilebaseline")) {
            boolean baseline = suite.equals("projectilebaseline");
            int cases = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (baseline && !id.equals(baselineItem)) continue;
                String ammo = "minecraft:arrow";
                int duration = 36;
                boolean selected = item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem;
                if (item instanceof TridentItem) ammo = "minecraft:air";
                if (id.startsWith("aether:") && id.endsWith("_dart_shooter")) {
                    selected = true; ammo = id.replace("_shooter", ""); duration = 14;
                } else if (id.equals("create:potato_cannon")) {
                    selected = true; ammo = "minecraft:potato"; duration = 1;
                } else if (id.equals("supplementaries:slingshot")) {
                    selected = true; ammo = "minecraft:cobblestone";
                } else if (id.equals("cataclysm:cursed_bow")) {
                    selected = true;
                } else if (id.equals("the_bumblezone:crystal_cannon")) {
                    selected = true; ammo = "the_bumblezone:honey_crystal_shards";
                } else if (id.equals("powergrid:electrozapper")) {
                    selected = true; ammo = "minecraft:air"; duration = 1;
                } else if (isSubclass(item.getClass(), "net.sweenus.simplyswords.item.SimplySwordsThrowableItem")) {
                    selected = true; ammo = "minecraft:air"; duration = 1;
                } else if (Set.of("minecraft:snowball", "minecraft:egg", "minecraft:ender_pearl",
                        "minecraft:experience_bottle", "minecraft:splash_potion", "minecraft:lingering_potion").contains(id)) {
                    selected = true; ammo = "minecraft:air"; duration = 1;
                }
                if (!selected) {
                    if (item instanceof ProjectileWeaponItem) log.accept("PROJECTILE NEEDS_FIXTURE item=" + id);
                    continue;
                }
                for (InteractionHand hand : InteractionHand.values()) {
                    addProjectileCheck(id, ammo, duration, hand, baseline);
                    cases++;
                }
            }
            log.accept("PROJECTILE INVENTORY scheduledCases=" + cases + " hands=both source=live_registry");
            add(10, m -> finish(m, "DONE"));
            return;
        }
        if (suite.equals("melee-diagnostic")) {
            for (String id : completedOffsetMelee.stream().sorted().toList()) {
                addMeleeCheck(id, "first_person");
                addMeleeCheck(id, "offset");
            }
            log.accept("MELEE INVENTORY scheduledCases=" + completedOffsetMelee.size() * 2
                    + " selectedItems=" + completedOffsetMelee.size() + " views=first_person,offset source=latest_offset_nonpass");
            add(10, m -> finish(m, "DONE"));
            return;
        }
        if (suite.equals("melee")) {
            int cases = 0, total = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                var modifiers = item.getDefaultInstance().get(DataComponents.ATTRIBUTE_MODIFIERS);
                boolean damageModifier = modifiers != null && modifiers.modifiers().stream().anyMatch(entry ->
                        entry.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
                if (item instanceof SwordItem || item instanceof DiggerItem || item instanceof TridentItem || damageModifier) {
                    total++;
                    String id = BuiltInRegistries.ITEM.getKey(item).toString();
                    if (offsetMeleeMode && completedOffsetMelee.contains(id)) continue;
                    addMeleeCheck(id);
                    cases++;
                }
            }
            log.accept("MELEE INVENTORY scheduledCases=" + cases + " scheduledTotal=" + total + " pending=" + cases
                    + " preservedPass=" + (total - cases) + " resume=" + offsetMeleeResuming
                    + " source=live_registry mode=" + (offsetMeleeMode ? "offset" : "freelook"));
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
            if (deferredFinishStatus != null) {
                // Do not queue restoration while a server setup/action can
                // still mutate this scene through a reentrant chunk wait.
                if (!serverTask.isDone()) return;
                if (ackNeeded) {
                    ackNeeded = false; pendingToken = ++nextToken;
                    mc.player.connection.sendCommand(ACK_COMMAND + " " + pendingToken); return;
                }
                if (pendingToken != 0) {
                    if (acknowledged != pendingToken) return;
                    if (!acknowledgedTeleport) {
                        pendingToken = ++nextToken; mc.player.connection.sendCommand(ACK_COMMAND + " " + pendingToken); return;
                    }
                    pendingToken = 0;
                }
                String status = deferredFinishStatus; deferredFinishStatus = null;
                finish(mc, status); return;
            }
            // Hold the fixture's orientation steady through BTP and cosmetic bow follow.
            float fixtureYaw = abilityFirstPersonMode ? 60 : 0;
            mc.player.setYRot(fixtureYaw); mc.player.yRotO = fixtureYaw;
            mc.player.setXRot(0); mc.player.xRotO = 0;
            btpFacing(fixtureYaw, 0);
            if (freelookProjectileMode || abilityPairMode) shoulder(mc);
            fixtureCamera(mc);
            if (shot != null) {
                Shot current = shot;
                if (observationTask.isDone()) {
                    UUID playerId = mc.player.getUUID();
                    observationTask = mc.getSingleplayerServer().submit(() -> {
                        ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                        if (running && shot == current && player != null) observeProjectiles(player, current);
                    });
                }
            }
            if (++barrierAge > 2400) throw new IllegalStateException("QA server/packet barrier timed out; action not certified");
            if (!serverTask.isDone()) return;
            serverTask.join();
            if (ackNeeded) {
                ackNeeded = false; pendingToken = ++nextToken;
                mc.player.connection.sendCommand(ACK_COMMAND + " " + pendingToken); return;
            }
            if (pendingToken != 0) {
                if (acknowledged != pendingToken) return;
                if (!acknowledgedTeleport) {
                    pendingToken = ++nextToken; mc.player.connection.sendCommand(ACK_COMMAND + " " + pendingToken); return;
                }
                pendingToken = 0;
            }
            if (wait > 0) {
                if (waitUntilServerTick < 0) waitUntilServerTick = mc.getSingleplayerServer().getTickCount() + wait;
                if (mc.getSingleplayerServer().getTickCount() < waitUntilServerTick) return;
                wait = 0; waitUntilServerTick = -1;
            }
            barrierAge = 0;
            Step step = steps.poll();
            if (step == null) { finish(mc, "DONE"); return; }
            step.action.run(mc);
            wait = step.delayAfter;
            waitUntilServerTick = -1;
            if (running) ackNeeded = true;
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
        addMeleeCheck(item, offsetMeleeMode ? "offset" : "freelook");
    }

    private static void addMeleeCheck(String item, String cameraMode) {
        String caseLabel = "MELEE " + item + (meleePathDiagnostic ? " " + cameraMode : "");
        // Register on this fresh QA class instance; an already-loaded wrapper
        // cannot acquire newly-added callback Methods by recompilation alone.
        if (!meleeListenersRegistered) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, IntegrationSceneProbe::meleeAttackStarted);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, IntegrationSceneProbe::meleeAttackObserved);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, IntegrationSceneProbe::meleeDamageObserved);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, IntegrationSceneProbe::meleeClickStarted);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, IntegrationSceneProbe::meleeClickObserved);
            meleeListenersRegistered = true;
        }
        boolean[] requested = {false};
        int[] readinessTicks = {0};
        Cow[] recordedVictim = {null};
        Vec3[] recordedPosition = {null};
        add(20, m -> {
            equip(m, item);
            if (cameraMode.equals("offset")) shoulder(m);
            else {
                m.options.setCameraType(CameraType.FIRST_PERSON);
                field("active").setBoolean(null, false);
                ((KeyMapping) field("FREELOOK_KEY").get(null)).setDown(cameraMode.equals("freelook"));
                field("freelookYaw").setFloat(null, cameraMode.equals("freelook") ? 75 : 0);
                field("freelookPitch").setFloat(null, 0);
            }
            fixtureCamera(m);
            Vec3 cameraTarget = cameraMode.equals("offset")
                    ? UnlockedCameraClient.crosshairRayGapFreeOrigin(m.player).add(UnlockedCameraClient.crosshairRayDirection(m.player).scale(2))
                    : PLAYER.add(Vec3.directionFromRotation(0, cameraMode.equals("freelook") ? 75 : 0).scale(2));
            server(m, p -> {
                meleeTrace = null;
                cleanupEntities(p);
                Vec3 at = cameraTarget;
                spawnCow(p, new Vec3(at.x, PLAYER.y + .35, at.z));
                Entity spawned = victim == null ? null : p.serverLevel().getEntity(victim);
                if (spawned instanceof Cow cow) {
                    if (item.equals("create:cardboard_sword")) {
                        // Only this case measures displacement: keep physical
                        // travel active without allowing autonomous movement.
                        cow.setNoAi(false);
                        cow.goalSelector.removeAllGoals(goal -> true);
                        cow.targetSelector.removeAllGoals(goal -> true);
                        cow.getNavigation().stop();
                        cow.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0);
                    }
                    recordedVictim[0] = cow;
                    recordedPosition[0] = cow.position();
                    meleeTrace = new MeleeTrace(cow, p.getUUID(), item);
                }
            });
        });
        Action[] attempt = new Action[1];
        attempt[0] = m -> {
            ItemStack expected = BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)).getDefaultInstance();
            try {
                expected = (ItemStack) Class.forName("net.sweenus.simplyswords.util.LegacyUniqueMigration")
                        .getMethod("convertStack", ItemStack.class).invoke(null, expected);
            } catch (ClassNotFoundException absent) { /* optional migration API */ }
            boolean equipped = m.player.getMainHandItem().getItem() == expected.getItem();
            float strength = m.player.getAttackStrengthScale(0);
            if (!equipped || strength < .99999f || m.player.isUsingItem()) {
                if (++readinessTicks[0] < 400) steps.addFirst(new Step(0, attempt[0]));
                else output.accept(caseLabel + " INCONCLUSIVE equipment/cooldown readiness timed out strength=" + strength);
                return;
            }
            MeleeTrace trace = meleeTrace;
            if (trace == null || trace.target != recordedVictim[0]) {
                output.accept(caseLabel + " INCONCLUSIVE exact server victim unavailable"); return;
            }
            if (!trace.preflightDone) {
                Item expectedServerItem = expected.getItem();
                server(m, p -> {
                    trace.preAttackHealth = trace.target.getHealth();
                    trace.pristine = trace.target.isAlive() && trace.preAttackHealth == 100 && trace.damageBeforeRequest == 0
                            && !trace.contaminated && p.getMainHandItem().getItem() == expectedServerItem;
                    trace.preflightDone = true;
                    output.accept("MELEE PREFLIGHT item=" + item + " mode=" + cameraMode + " victim=" + trace.target.getUUID() + " health=" + trace.preAttackHealth
                            + " pristine=" + trace.pristine + " damageBeforeRequest=" + trace.damageBeforeRequest
                            + " actualServerHeld=" + BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem())
                            + (meleePathDiagnostic ? " actor=" + p.getUUID() + " victimId=" + trace.target.getId()
                                + " actorPosition=" + p.position() + " victimPosition=" + trace.target.position()
                                + " canInteract=" + p.canInteractWithEntity(trace.target.getBoundingBox(), 1.0)
                                + " entityRange=" + p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE) : ""));
                });
                steps.addFirst(new Step(0, attempt[0])); return;
            }
            if (!trace.pristine || trace.contaminated) {
                output.accept(caseLabel + " INCONCLUSIVE pre-attack-damage-or-server-equipment health=" + trace.preAttackHealth
                        + " damageBeforeRequest=" + trace.damageBeforeRequest + " sources=" + trace.damageSources); return;
            }
            m.gameRenderer.pick(1);
            if (meleePathDiagnostic) output.accept("MELEE CLIENT_PREFLIGHT item=" + item + " mode=" + cameraMode
                    + " " + meleeClientDiagnostic(m) + " expectedVictim=" + trace.target.getUUID()
                    + " exactPickedVictim=" + (m.hitResult instanceof EntityHitResult hit && hit.getEntity().getUUID().equals(trace.target.getUUID())));
            Method attack = Minecraft.class.getDeclaredMethod("startAttack");
            attack.setAccessible(true);
            trace.requested = true;
            output.accept("MELEE REQUEST item=" + item + " mode=" + cameraMode + " cooldown=" + strength
                    + " actualHeld=" + BuiltInRegistries.ITEM.getKey(m.player.getMainHandItem().getItem())
                    + " hit=" + m.hitResult + " result=" + attack.invoke(m));
            if (meleePathDiagnostic) output.accept("MELEE CLIENT_AFTER item=" + item + " mode=" + cameraMode + " " + meleeClientDiagnostic(m));
            requested[0] = true;
            steps.addFirst(new Step(35, ignored -> {}));
        };
        add(0, attempt[0]);
        add(10, m -> server(m, p -> {
            if (!requested[0]) { meleeTrace = null; cleanupEntities(p); return; }
            Entity entity = victim == null ? null : p.serverLevel().getEntity(victim);
            // Keep the created cow reference: a lethal strike removes it before
            // this observation and is successful damage rather than a miss.
            float health = recordedVictim[0] != null ? recordedVictim[0].getHealth() : Float.NaN;
            double displacement = recordedVictim[0] != null && recordedPosition[0] != null
                    ? recordedVictim[0].position().distanceTo(recordedPosition[0]) : 0;
            boolean cardboard = item.equals("create:cardboard_sword");
            MeleeTrace trace = meleeTrace;
            String evidence = "victimPresent=" + (entity instanceof Cow) + " remainingHealth=" + health + " displacement=" + displacement
                    + " preAttackHealth=" + (trace == null ? Float.NaN : trace.preAttackHealth)
                    + " healthAtFirstAttack=" + (trace == null ? Float.NaN : trace.healthAtFirstAttack)
                    + " serverAttackEvents=" + (trace == null ? 0 : trace.attackEvents)
                    + " canceledAttackEvents=" + (trace == null ? 0 : trace.canceledEvents)
                    + (meleePathDiagnostic ? " clientAttackEvents=" + (trace == null ? 0 : trace.clientAttackEvents)
                        + " clickEvents=" + (trace == null ? 0 : trace.clickEvents)
                        + " canceledClickEvents=" + (trace == null ? 0 : trace.canceledClickEvents) : "")
                    + " attributedDamageEvents=" + (trace == null ? 0 : trace.attributedDamageEvents)
                    + " attributedPlayerAttackDamage=" + (trace == null ? 0 : trace.attributedDamage)
                    + " attributedCustomSourceEvents=" + (trace == null ? 0 : trace.attributedCustomDamageEvents)
                    + " attributedCustomSourceDamage=" + (trace == null ? 0 : trace.attributedCustomDamage)
                    + " allowedCustomSource=" + (item.equals("twilightforest:stale_bread") ? "twilightforest:stale_sandwich" : "none")
                    + " unattributedDamage=" + (trace == null ? 0 : trace.unattributedDamage)
                    + " damageSources=" + (trace == null ? "[]" : trace.damageSources)
                    + " oracle=" + (cardboard ? "canceled_server_attack_with_knockback"
                        : item.equals("twilightforest:stale_bread") ? "server_attack_and_exact_stale_sandwich_source"
                        : "server_attack_and_direct_player_attack_damage");
            if (trace == null || !trace.pristine || trace.contaminated)
                output.accept(caseLabel + " INCONCLUSIVE pre-attack-damage-or-fixture-contamination " + evidence);
            else if (!cardboard && trace.attackEvents > 0 && trace.attributedDamageEvents + trace.attributedCustomDamageEvents == 0 && trace.unattributedDamage > 0)
                output.accept(caseLabel + " INCONCLUSIVE damage-source-requires-special-oracle " + evidence);
            else result(caseLabel, trace.attackEvents > 0 && (cardboard
                            ? trace.canceledEvents > 0 && health == 100 && displacement > .1 && trace.impactVelocity.horizontalDistanceSqr() > .001
                            : trace.attributedDamageEvents + trace.attributedCustomDamageEvents > 0
                                && trace.attributedDamage + trace.attributedCustomDamage > 0 && health >= 0 && health < trace.preAttackHealth), evidence);
            meleeTrace = null;
            if (recordedVictim[0] != null) recordedVictim[0].discard();
            cleanupEntities(p);
        }));
    }

    private static String meleeClientDiagnostic(Minecraft mc) throws Exception {
        Field miss = Minecraft.class.getDeclaredField("missTime"); miss.setAccessible(true);
        Object attributes = Class.forName("net.bettercombat.logic.WeaponRegistry")
                .getMethod("getAttributes", ItemStack.class).invoke(null, mc.player.getMainHandItem());
        return "missTime=" + miss.getInt(mc) + " handsBusy=" + mc.player.isHandsBusy()
                + " itemEnabled=" + mc.player.getMainHandItem().isItemEnabled(mc.level.enabledFeatures())
                + " using=" + mc.player.isUsingItem() + " attackKey=" + mc.options.keyAttack.isDown()
                + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                + " betterCombatAttributes=" + (attributes != null) + " hit=" + describeHit(mc.hitResult)
                + " crosshairEntity=" + describeEntity(mc.crosshairPickEntity);
    }

    private static String describeHit(HitResult hit) {
        return hit instanceof EntityHitResult entity ? "ENTITY:" + describeEntity(entity.getEntity())
                : hit == null ? "null" : hit.getType() + ":" + hit.getLocation();
    }

    private static String describeEntity(Entity entity) {
        return entity == null ? "null" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                + ":id=" + entity.getId() + ":uuid=" + entity.getUUID() + ":position=" + entity.position();
    }

    private static void addProjectileCheck(String item) {
        addProjectileCheck(item, "minecraft:arrow", 36);
    }

    /** Real Simply Swords key handler and packet; inspect its server-selected target. */
    private static void addAbilityCheck() {
        addAbilityCheck(null);
    }

    private static void addAbilityCheck(String view) {
        add(25, m -> {
            if (view != null) {
                abilityView = view;
                abilityFirstPersonMode = view.equals("first_person");
                freelookProjectileMode = view.equals("freelook");
                m.player.setYRot(abilityFirstPersonMode ? 60 : 0);
                m.player.yRotO = m.player.getYRot();
                btpFacing(m.player.getYRot(), 0);
            }
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
            Vec3 at = view != null ? m.player.getEyePosition().add(Vec3.directionFromRotation(0, 60).scale(3))
                    : UnlockedCameraClient.crosshairRayGapFreeOrigin(m.player)
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
                    p.containerMenu.broadcastFullState();
                    output.accept("SIMPLY_SWORDS_ABILITY fixture awakened="
                            + awakening.getMethod("isAbilityUnlocked", ItemStack.class).invoke(null, awakened));
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                cleanupEntities(p);
                p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
                float setupYaw = view != null && view.equals("first_person") ? 60 : 0;
                p.setYRot(setupYaw); p.setXRot(0); p.setYHeadRot(setupYaw);
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
        if (view != null) add(4, m -> {
            // The preceding 25 server ticks keep the physical key UP. Never
            // reset the handler's private pressed flags to force a transition.
            boolean unlocked = (boolean) Class.forName("net.sweenus.simplyswords.api.AwakeningApi")
                    .getMethod("isAbilityUnlocked", ItemStack.class).invoke(null, m.player.getMainHandItem());
            boolean pressed = abilityHandlerPressed();
            output.accept("SIMPLY_SWORDS_ABILITY PREFLIGHT_CLIENT mode=" + view + " " + abilityClientDiagnostic(m)
                    + " awakened=" + unlocked + " keyUpServerTicks=25");
            if (abilityKey.isDown() || pressed || !unlocked || !m.player.getMainHandItem().is(
                    BuiltInRegistries.ITEM.get(ResourceLocation.parse("simplyswords:magispear"))))
                throw new IllegalStateException("Ability fixture client stack/key-up readiness failed: " + view);
            server(m, p -> {
                try { output.accept("SIMPLY_SWORDS_ABILITY PREFLIGHT_SERVER mode=" + view + " " + abilityServerDiagnostic(p)); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            });
        });
        add(8, m -> {
            m.gameRenderer.pick(1);
            if (!(m.hitResult instanceof EntityHitResult hit) || !hit.getEntity().getUUID().equals(victim))
                throw new IllegalStateException("Ability/entity-ping fixture is not the exact zombie under the crosshair: " + m.hitResult);
            if (view == null) PingActionProbe.start(m, output);
        });
        add(8, m -> {
            m.gameRenderer.pick(1);
            output.accept("SIMPLY_SWORDS_ABILITY PRESS item=simplyswords:magispear bodyYaw="
                    + m.player.getYRot() + " cameraType=" + m.options.getCameraType()
                    + " freelook=" + freelookProjectileMode + " mode=" + abilityView + " hit=" + m.hitResult
                    + (view == null ? "" : " " + abilityClientDiagnostic(m)));
            pendingAbilityPress = true;
        });
        if (view != null) add(2, m -> {
            output.accept("SIMPLY_SWORDS_ABILITY INPUT_OBSERVED mode=" + view + " " + abilityClientDiagnostic(m));
            server(m, p -> {
                try { output.accept("SIMPLY_SWORDS_ABILITY REQUEST_SERVER mode=" + view + " " + abilityServerDiagnostic(p)); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            });
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
                            "mode=" + abilityView + " active=" + (ability != null) + " center=" + center + " victim="
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

    private static boolean abilityHandlerPressed() throws ReflectiveOperationException {
        Field pressed = Class.forName("net.sweenus.simplyswords.client.AbilityKeybindHandler").getDeclaredField("mainhandPressed");
        pressed.setAccessible(true); return pressed.getBoolean(null);
    }

    private static String abilityClientDiagnostic(Minecraft mc) throws ReflectiveOperationException {
        return "keyDown=" + abilityKey.isDown() + " keyBound=" + !abilityKey.isUnbound()
                + " handlerPressed=" + abilityHandlerPressed() + " held=" + BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem())
                + " clientCooldown=" + mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem().getItem())
                + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                + " effects=" + mc.player.getActiveEffects() + " clientTick=" + mc.player.tickCount
                + " bodyYaw=" + mc.player.getYRot() + " cameraYaw=" + mc.gameRenderer.getMainCamera().getYRot();
    }

    private static String abilityServerDiagnostic(ServerPlayer player) throws ReflectiveOperationException {
        ItemStack stack = player.getMainHandItem();
        boolean incapacitated = (boolean) Class.forName("net.sweenus.simplyswords.api.IncapacitatingStatusEffectRegistry")
                .getMethod("isIncapacitated", LivingEntity.class).invoke(null, player);
        boolean customCooldown = (boolean) Class.forName("net.sweenus.simplyswords.world.WeaponAbilityCooldownManager")
                .getMethod("isCoolingDown", ServerLevel.class, LivingEntity.class, ItemStack.class)
                .invoke(null, player.serverLevel(), player, stack);
        boolean active = (boolean) Class.forName("net.sweenus.simplyswords.world.MagispearAbilityManager")
                .getMethod("isActive", LivingEntity.class).invoke(null, player);
        return "held=" + BuiltInRegistries.ITEM.getKey(stack.getItem()) + " damage=" + stack.getDamageValue()
                + " vanillaCooldown=" + player.getCooldowns().isOnCooldown(stack.getItem()) + " abilityCooldown=" + customCooldown
                + " incapacitated=" + incapacitated + " effects=" + player.getActiveEffects() + " active=" + active
                + " yaw=" + player.getYRot() + " headYaw=" + player.getYHeadRot() + " gameTime=" + player.serverLevel().getGameTime();
    }

    private static void addProjectileCheck(String item, String ammo, int useTicks) {
        addProjectileCheck(item, ammo, useTicks, InteractionHand.MAIN_HAND);
    }

    private static void addProjectileCheck(String item, String ammo, int useTicks, InteractionHand hand) {
        addProjectileCheck(item, ammo, useTicks, hand, false);
    }

    private static void addProjectileCheck(String item, String ammo, int useTicks, InteractionHand hand, boolean baseline) {
        String label = item + " hand=" + hand;
        int[] readinessTicks = {0};
        add(20, m -> {
            shot = null;
            equip(m, item, ammo, hand);
            shoulder(m);
            if (baseline) {
                field("active").setBoolean(null, false);
                m.options.setCameraType(CameraType.FIRST_PERSON);
            }
            server(m, p -> {
                cleanupEntities(p);
                p.teleportTo(PLAYER.x, PLAYER.y, PLAYER.z);
                for (BlockPos pos : BlockPos.betweenClosed(1704, 150, 191, 1717, 156, 194))
                    p.serverLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                p.serverLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                // Wall provides a stable convergence target beyond ordinary interaction reach.
                for (BlockPos pos : BlockPos.betweenClosed(1704, 150, 195, 1717, 155, 195)) {
                    p.serverLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                }
            });
        });
        Action[] fire = new Action[1];
        fire[0] = m -> {
            Item expected = BuiltInRegistries.ITEM.get(ResourceLocation.parse(item));
            if (hand == InteractionHand.OFF_HAND && m.player.getItemInHand(hand).isEmpty()
                    && m.player.getInventory().offhand.getFirst().getItem() == expected) {
                output.accept("PROJECTILE SKIP suppressed offhand item=" + label
                        + " rawSlot=" + BuiltInRegistries.ITEM.getKey(m.player.getInventory().offhand.getFirst().getItem())
                        + " equipmentGetter=minecraft:air;not_a_gameplay_pass");
                return;
            }
            if (m.player.getItemInHand(hand).getItem() != expected || m.player.isUsingItem()) {
                if (++readinessTicks[0] > 1200) throw new IllegalStateException("Projectile fixture equipment readiness timeout: " + label
                        + " actual=" + BuiltInRegistries.ITEM.getKey(m.player.getItemInHand(hand).getItem()));
                steps.addFirst(new Step(0, fire[0])); return;
            }
            float[] aim = baseline ? new float[]{m.player.getYRot(), m.player.getXRot()}
                    : UnlockedCameraClient.crosshairAimAngles();
            if (aim == null) throw new IllegalStateException("Projectile aim missing for " + item);
            // Vanilla bottles deliberately lob twenty degrees above player pitch.
            float launchPitch = aim[1] - (Set.of("minecraft:experience_bottle", "minecraft:splash_potion",
                    "minecraft:lingering_potion").contains(item) ? 20 : 0);
            shot = new Shot(label, Vec3.directionFromRotation(launchPitch, aim[0]));
            double bodyDot = shot.expected.normalize().dot(Vec3.directionFromRotation(m.player.getXRot(), m.player.getYRot()));
            if (!baseline && bodyDot >= .995) throw new IllegalStateException("Projectile fixture cannot distinguish body aim: dot=" + bodyDot);
            output.accept("PROJECTILE USE item=" + label + " ammo=" + ammo + " duration=" + useTicks + " aim=" + aim[0] + "," + aim[1]
                    + " bodyDirectionDot=" + bodyDot
                    + " actualHeld=" + BuiltInRegistries.ITEM.getKey(m.player.getItemInHand(hand).getItem())
                    + " result=" + m.gameMode.useItem(m.player, hand));
            m.options.keyUse.setDown(true);
            steps.addFirst(new Step(useTicks, ignored -> {}));
        };
        add(0, fire[0]);
        add(5, m -> {
            m.options.keyUse.setDown(false);
            m.gameMode.releaseUsingItem(m.player);
        });
        add(15, m -> {
            if (m.player.getItemInHand(hand).getItem() instanceof CrossbowItem) {
                output.accept("PROJECTILE FIRE_CROSSBOW " + m.gameMode.useItem(m.player, hand));
            }
        });
        add(5, m -> {
            Shot current = shot;
            if (current != null) result((baseline ? "PROJECTILE_BASELINE " : "PROJECTILE ") + label, current.observed > 0 && current.bestDot > .995,
                    "observed=" + current.observed + " bestDirectionDot=" + current.bestDot);
            shot = null;
        });
    }

    private static void observeProjectiles(ServerPlayer player, Shot current) {
        if (!running || shot != current) return;
        for (Projectile projectile : player.serverLevel().getEntitiesOfClass(Projectile.class, AREA)) {
            if (projectile.getOwner() != player || current.seen.contains(projectile.getUUID())) continue;
            Vec3 velocity = projectile.getDeltaMovement();
            if (velocity.lengthSqr() < .01 || projectile.tickCount > 2) continue;
            sampleProjectile(projectile, current);
        }
    }

    private static void sampleProjectile(Projectile projectile, Shot current) {
        Vec3 velocity = projectile.getDeltaMovement();
        if (current.seen.contains(projectile.getUUID()) || velocity.lengthSqr() < .01) return;
        current.seen.add(projectile.getUUID());
        projectile.addTag(TAG);
        double fullDot = velocity.normalize().dot(current.expected.normalize());
        // Crystal Cannon adds random upward launch velocity by design; compare
        // its horizontal heading, recording the full 3-D dot separately.
        boolean headingOnly = current.item.startsWith("the_bumblezone:crystal_cannon ");
        double dot = headingOnly
                ? new Vec3(velocity.x, 0, velocity.z).normalize().dot(new Vec3(current.expected.x, 0, current.expected.z).normalize())
                : fullDot;
        current.bestDot = Math.max(current.bestDot, dot);
        current.observed++;
        output.accept("PROJECTILE SAMPLE item=" + current.item + " entity=" + BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType())
                + " ticks=" + projectile.tickCount + " velocity=" + velocity + " directionDot=" + dot
                + " fullDirectionDot=" + fullDot + " oracle=" + (headingOnly ? "horizontal-heading" : "launch-direction"));
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
        ((KeyMapping) field("FREELOOK_KEY").get(null)).setDown(freelookProjectileMode);
        field("freelookYaw").setFloat(null, freelookProjectileMode ? 60 : 0);
        field("freelookPitch").setFloat(null, 0);
        ClientConfig.TOGGLE_MOD.set(true);
        ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
        ClientConfig.SHOULDER_OFFSET_AMOUNT.set(.95);
        ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
        field("active").setBoolean(null, !freelookProjectileMode && !abilityFirstPersonMode);
        field("targetDistance").setFloat(null, 4);
        field("smoothedDistance").setFloat(null, 4);
        field("shoulderSide").setInt(null, 1);
        mc.options.setCameraType(freelookProjectileMode || abilityFirstPersonMode ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        fixtureCamera(mc);
    }

    private static void fixtureCamera(Minecraft mc) throws Exception {
        if (cameraPosition == null || mc.player == null) return;
        boolean first = mc.options.getCameraType() == CameraType.FIRST_PERSON;
        boolean free = first && ((KeyMapping) field("FREELOOK_KEY").get(null)).isDown();
        float yaw = free ? field("freelookYaw").getFloat(null) : mc.player.getYRot();
        float pitch = free ? field("freelookPitch").getFloat(null) : mc.player.getXRot();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 camera = first ? eye : eye.subtract(Vec3.directionFromRotation(pitch, yaw).scale(4))
                .add(Math.cos(Math.toRadians(yaw)) * .95, 0, Math.sin(Math.toRadians(yaw)) * .95);
        cameraPosition.invoke(mc.gameRenderer.getMainCamera(), camera);
        cameraRotation.invoke(mc.gameRenderer.getMainCamera(), yaw, pitch, 0f);
    }

    private static void btpFacing() throws Exception {
        btpFacing(0, 0);
    }

    private static void btpFacing(float yaw, float pitch) throws Exception {
        Object manager = Class.forName("io.socol.betterthirdperson.BetterThirdPerson").getMethod("getCameraManager").invoke(null);
        Object camera = manager.getClass().getMethod("getCustomCamera").invoke(manager);
        if (camera == null) return;
        Class<?> rotation = Class.forName("io.socol.betterthirdperson.api.util.Rotation");
        Object zero = rotation.getConstructor(float.class, float.class).newInstance(yaw, pitch);
        camera.getClass().getMethod("setCameraRotation", rotation).invoke(camera, zero);
        Field playerRotation = camera.getClass().getDeclaredField("playerRotation");
        playerRotation.setAccessible(true);
        playerRotation.set(camera, zero);
    }

    private static void equip(Minecraft mc, String id) {
        equip(mc, id, "minecraft:arrow");
    }

    private static void equip(Minecraft mc, String id, String ammo) {
        equip(mc, id, ammo, InteractionHand.MAIN_HAND);
    }

    private static void equip(Minecraft mc, String id, String ammo, InteractionHand hand) {
        mc.player.getInventory().selected = 0;
        server(mc, player -> {
            player.stopUsingItem(); player.closeContainer(); player.getInventory().clearContent();
            ItemStack weapon = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
            player.getInventory().setItem(0, hand == InteractionHand.MAIN_HAND ? weapon : ItemStack.EMPTY);
            player.getInventory().setItem(1, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(ammo)), 64));
            player.getInventory().offhand.set(0, hand == InteractionHand.OFF_HAND ? weapon : ItemStack.EMPTY);
            player.getCooldowns().removeCooldown(weapon.getItem());
            player.getInventory().selected = 0;
            player.containerMenu.broadcastFullState();
        });
    }

    private static boolean isSubclass(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) if (c.getName().equals(name)) return true;
        return false;
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
        List<Entity> remove = new ArrayList<>();
        for (Entity entity : player.serverLevel().getAllEntities())
            if (entity != null && entity.getTags().contains(TAG)) remove.add(entity);
        for (Entity entity : remove) if (!entity.isRemoved()) entity.discard();
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
        if (!serverTask.isDone() || ackNeeded || pendingToken != 0 && (acknowledged != pendingToken || !acknowledgedTeleport)) {
            if (deferredFinishStatus == null) {
                deferredFinishStatus = status;
                steps.clear(); shot = null; mc.options.keyUse.setDown(false);
                output.accept("INTEGRATION WAIT_DRAIN requestedStatus=" + status
                        + ";no-restoration-or-next-scene-until-server-tasks-and-packets-drain");
            }
            return;
        }
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
        if (offsetMeleeMode || freelookProjectileMode || standardRecheckMode) {
            ClientConfig.ENABLE_FREELOOK.set(savedFreelookEnabled); ClientConfig.FREELOOK_KEEP_DIRECTION.set(savedKeepDirection);
            ((KeyMapping) field("FREELOOK_KEY").get(null)).setDown(savedFreelookKey);
            field("freelookYaw").setFloat(null, savedFreelookYaw); field("freelookPitch").setFloat(null, savedFreelookPitch);
        }
        Method clearAim = UnlockedCameraClient.class.getDeclaredMethod("dropHeldAim");
        clearAim.setAccessible(true); clearAim.invoke(null);
        ServerSnapshot clientSnapshot = original;
        if (clientSnapshot != null) {
            mc.player.setYRot(clientSnapshot.yaw); mc.player.yRotO = clientSnapshot.yaw;
            mc.player.setXRot(clientSnapshot.pitch); mc.player.xRotO = clientSnapshot.pitch;
            btpFacing(clientSnapshot.yaw, clientSnapshot.pitch);
        }
        server(mc, player -> {
            clearCarry(player); cleanupEntities(player);
            ServerSnapshot snapshot = original;
            if (snapshot != null) {
                for (int i = 0; i < snapshot.inventory.size(); i++) player.getInventory().setItem(i, snapshot.inventory.get(i));
                player.getInventory().selected = snapshot.selected;
                player.setGameMode(snapshot.mode);
                player.teleportTo(player.serverLevel(), snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                player.setYHeadRot(snapshot.yaw);
                player.stopUsingItem(); player.containerMenu.broadcastFullState();
            }
            output.accept("INTEGRATION RESTORED");
            if ((offsetMeleeMode || freelookProjectileMode || standardRecheckMode) && status.equals("DONE")) output.accept("INTEGRATION DONE");
        });
        if (!(offsetMeleeMode || freelookProjectileMode || standardRecheckMode) || !status.equals("DONE")) output.accept("INTEGRATION " + status);
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
        CompletableFuture<Void> previousTask = serverTask;
        CompletableFuture<Void> nextTask = server.submit(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) throw new IllegalStateException("Test player absent from integrated server");
                action.accept(player);
            } catch (Throwable e) { output.accept("INTEGRATION SERVER_ERROR " + e); throw new IllegalStateException(e); }
        });
        // Chunk managedBlock may execute later queued tasks reentrantly. Keep
        // every mutation future, so a later completed task cannot mask an
        // earlier unfinished setup or its exception.
        serverTask = CompletableFuture.allOf(previousTask, nextTask);
    }

    private static boolean teleportReady(ServerPlayer player) {
        try {
            Field awaiting = player.connection.getClass().getDeclaredField("awaitingPositionFromClient");
            awaiting.setAccessible(true); return awaiting.get(player.connection) == null;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static boolean nestedInteraction() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace())
            if (frame.getClassName().equals("net.minecraft.server.network.ServerGamePacketListenerImpl")
                    && Set.of("handleUseItem", "handleUseItemOn", "handleInteract", "handlePlayerAction").contains(frame.getMethodName()))
                return true;
        return false;
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
