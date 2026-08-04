package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.*;
import com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat;
import com.raiiiden.ragdollified.client.compat.CuriosRenderCompat;
import com.raiiiden.ragdollified.client.compat.VisualHealthCompat;
import com.raiiiden.ragdollified.compat.CuriosCompat;
import com.raiiiden.ragdollified.client.compat.GeckoLibArmorHelper;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.*;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import net.minecraft.client.resources.DefaultPlayerSkin;

import javax.vecmath.Vector3f;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, value = Dist.CLIENT)
public class ClientRagdollRenderer {

    // Player models
    private static PlayerModel<AbstractClientPlayer> normalModel;
    private static PlayerModel<AbstractClientPlayer> slimModel;
    private static HumanoidModel<AbstractClientPlayer> normalArmorInner;
    private static HumanoidModel<AbstractClientPlayer> normalArmorOuter;
    private static HumanoidModel<AbstractClientPlayer> slimArmorInner;
    private static HumanoidModel<AbstractClientPlayer> slimArmorOuter;

    // Mob models
    private static HumanoidModel<?> standardHumanoidModel;
    private static SkeletonModel<?> skeletonModel;
    private static IllagerModel<?> illagerModel;
    // Villagers/wandering traders render on the vanilla VillagerModel (correct UVs for their
    // textures). Its single combined "arms" part (both stubs + the connecting bar) is anchored
    // to the torso body so the crossed-arms silhouette survives — see renderVillagerParts.
    private static ModelPart villagerRoot;
    // Zombie villagers use the vanilla ZombieVillagerModel (a HumanoidModel with real
    // separate arms and matching UVs) so they render through the standard humanoid path.
    private static ZombieVillagerModel<?> zombieVillagerModel;
    private static DrownedModel<?> drownedModel;
    private static CreeperModel<?> creeperModel;
    // PiglinModel extends PlayerModel/HumanoidModel with extra ear/nose/tusk cubes on the
    // head — using it instead of the bare HumanoidModel for piglin/piglin_brute/zombified
    // so the ears actually render. Without this they look like bald zombies.
    private static net.minecraft.client.model.PiglinModel<?> piglinModel;

    // Overlay models — second-layer copies baked from inflated layer definitions.
    // Mirror the base humanoid/quadruped part structure exactly so the same setPos /
    // physics-driven rotations apply unchanged.
    private static DrownedModel<?> drownedOuterModel;     // sea-grass over drowned body
    private static SkeletonModel<?> strayClothingModel;   // tattered clothes over stray
    private static CreeperModel<?> creeperPoweredModel;   // electric swirl on charged creepers
    private static ModelPart pigSaddleRoot;               // saddle on saddled pigs

    // Mob armor
    private static HumanoidModel<?> mobArmorInner;
    private static HumanoidModel<?> mobArmorOuter;

    // Quadruped roots
    private static ModelPart cowRoot;
    private static ModelPart sheepRoot;
    private static ModelPart sheepFurRoot; // wool overlay layer for non-sheared sheep
    private static ModelPart pigRoot;
    private static ModelPart chickenRoot;
    // Cat/ocelot share the OcelotModel geometry (quadruped + tail). Bat and bee are winged.
    // Baked ModelPart trees are used directly (parts are driven per-body by the renderer), so
    // the EntityModel wrappers aren't needed — and BeeModel (AgeableListModel) has no root().
    private static ModelPart catRoot;
    private static ModelPart catCollarRoot; // dyed collar overlay for tamed cats
    private static ModelPart batRoot;
    private static ModelPart beeRoot;
    private static ModelPart witchRoot;
    private static ModelPart horseRoot;
    private static ModelPart donkeyRoot;
    private static ModelPart muleRoot;
    private static ModelPart skeletonHorseRoot;
    private static ModelPart zombieHorseRoot;
    private static ModelPart wolfRoot;
    private static ModelPart foxRoot;
    private static ModelPart pandaRoot;
    private static ModelPart ironGolemRoot;
    private static ModelPart goatRoot;
    private static ModelPart polarBearRoot;
    private static ModelPart turtleRoot;
    private static ModelPart endermanRoot;
    private static ModelPart camelRoot;
    private static ModelPart llamaRoot;
    private static ModelPart llamaDecorRoot;
    private static ModelPart rabbitRoot;
    private static ModelPart frogRoot;
    private static ModelPart hoglinRoot;
    private static ModelPart snifferRoot;
    private static ModelPart ravagerRoot;
    private static ModelPart phantomRoot;
    private static ModelPart parrotRoot;
    private static ModelPart slimeInnerRoot;
    private static ModelPart slimeOuterRoot;
    private static ModelPart magmaCubeRoot;
    private static ModelPart silverfishRoot;
    private static ModelPart endermiteRoot;
    private static ModelPart allayRoot;
    private static ModelPart striderRoot;
    private static ModelPart striderSaddleRoot;
    private static ModelPart snowGolemRoot;
    private static ModelPart blazeRoot;
    private static ModelPart spiderRoot;
    private static ModelPart caveSpiderRoot;
    private static ModelPart shulkerRoot;
    private static ModelPart ghastRoot;
    private static ModelPart vexRoot;
    private static ModelPart wardenRoot;

    private static final ResourceLocation CAT_COLLAR_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/cat/cat_collar.png");
    private static final ResourceLocation WOLF_COLLAR_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/wolf/wolf_collar.png");
    private static final ResourceLocation IRON_GOLEM_HIGH_CRACKS_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/iron_golem/iron_golem_crackiness_high.png");
    private static final ResourceLocation ENDERMAN_EYES_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/enderman/enderman_eyes.png");
    private static final ResourceLocation PHANTOM_EYES_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/phantom_eyes.png");
    private static final ResourceLocation TRADER_LLAMA_DECOR_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/llama/decor/trader_llama.png");
    private static final ResourceLocation STRIDER_SADDLE_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/strider/strider_saddle.png");
    private static final ResourceLocation SPIDER_EYES_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/spider_eyes.png");
    private static final ResourceLocation WARDEN_BIOLUMINESCENT_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/warden/warden_bioluminescent_layer.png");

    private static final ResourceLocation SHEEP_FUR_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/sheep/sheep_fur.png");
    private static final ResourceLocation DROWNED_OUTER_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/zombie/drowned_outer_layer.png");
    private static final ResourceLocation STRAY_OUTER_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/skeleton/stray_overlay.png");
    private static final ResourceLocation CREEPER_POWERED_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/creeper/creeper_armor.png");
    private static final ResourceLocation PIG_SADDLE_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/pig/pig_saddle.png");
    private static final ResourceLocation[] HORSE_MARKING_TEXTURES = {
            null,
            new ResourceLocation("minecraft", "textures/entity/horse/horse_markings_white.png"),
            new ResourceLocation("minecraft", "textures/entity/horse/horse_markings_whitefield.png"),
            new ResourceLocation("minecraft", "textures/entity/horse/horse_markings_whitedots.png"),
            new ResourceLocation("minecraft", "textures/entity/horse/horse_markings_blackdots.png")
    };

    // Reusable quaternion to avoid per-part allocations
    private static final Quaternionf tempQuat = new Quaternionf();

    private static boolean initialized = false;

    // Render-thread timing exposed for ClientRagdollManager's perf log. Distinguishes
    // FPS drops caused by the render path (this class) vs the tick path (the manager).
    public static volatile long lastRenderFrameNanos = 0;
    public static volatile int lastRenderedCount = 0;
    public static volatile int lastCulledCount = 0;
    private static final long[] renderFrameRing = new long[60]; // ~1s at 60fps
    private static int renderFrameRingIdx = 0;
    private static int renderFrameRingFilled = 0;

    public static long avgRenderFrameNanos() {
        if (renderFrameRingFilled == 0) return 0;
        long sum = 0;
        for (int i = 0; i < renderFrameRingFilled; i++) sum += renderFrameRing[i];
        return sum / renderFrameRingFilled;
    }

    private static void initModels() {
        if (initialized) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            var bakery = mc.getEntityModels();

            // Fresh vanilla player trees, deliberately bypassing EntityModelSet/bakeLayer.
            // EMF/Fresh Moves can replace or mutate the registered PLAYER/PLAYER_SLIM layers;
            // taking those baked trees lets its animation geometry corrupt physics-part pivots.
            // Direct LayerDefinitions match the isolated standard-zombie path below while
            // retaining separate Steve (wide-arm) and Alex (slim-arm) geometry.
            LayerDefinition steveDef = LayerDefinition.create(
                    PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
            LayerDefinition alexDef = LayerDefinition.create(
                    PlayerModel.createMesh(CubeDeformation.NONE, true), 64, 64);
            normalModel = new PlayerModel<>(steveDef.bakeRoot(), false);
            slimModel = new PlayerModel<>(alexDef.bakeRoot(), true);

            // Isolate player armor layers too. These are vanilla's inner/outer armor
            // deformations and 64x32 texture layout; each model gets its own mutable tree.
            normalArmorInner = freshPlayerArmor(0.5F);
            normalArmorOuter = freshPlayerArmor(1.0F);
            slimArmorInner = freshPlayerArmor(0.5F);
            slimArmorOuter = freshPlayerArmor(1.0F);

            // Mob models
            LayerDefinition standardDef = LayerDefinition.create(
                    HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F), 64, 64);
            standardHumanoidModel = new HumanoidModel<>(standardDef.bakeRoot());

            skeletonModel = new SkeletonModel<>(SkeletonModel.createBodyLayer().bakeRoot());
            illagerModel = new IllagerModel<>(IllagerModel.createBodyLayer().bakeRoot());
            // Villager base model (vanilla, correct UVs); its combined arms ride the torso.
            // Zombie villagers get their own vanilla model which has real separate arms.
            // Isolated from the bakery for the same reason the player trees above are: EMF /
            // Fresh Animations replaces the registered VILLAGER and ZOMBIE_VILLAGER layers with
            // its own animated geometry, and a ragdoll baked from those inherits the animation
            // instead of lying still. Vanilla LayerDefinitions give each a private tree.
            villagerRoot = LayerDefinition.create(
                    net.minecraft.client.model.VillagerModel.createBodyModel(), 64, 64).bakeRoot();
            zombieVillagerModel = new ZombieVillagerModel<>(
                    ZombieVillagerModel.createBodyLayer().bakeRoot());
            drownedModel = new DrownedModel<>(DrownedModel.createBodyLayer(CubeDeformation.NONE).bakeRoot());
            creeperModel = new CreeperModel<>(CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot());
            // Use the bakery's already-baked PIGLIN layer so we share the same ModelPart
            // tree (and the same cube inflations) as the live PiglinRenderer.
            piglinModel = new net.minecraft.client.model.PiglinModel<>(
                    bakery.bakeLayer(net.minecraft.client.model.geom.ModelLayers.PIGLIN));

            // Overlay models — baked from the same vanilla layer-definition keys the
            // entity renderer uses, so the per-cube inflation matches exactly.
            drownedOuterModel = new DrownedModel<>(bakery.bakeLayer(ModelLayers.DROWNED_OUTER_LAYER));
            strayClothingModel = new SkeletonModel<>(bakery.bakeLayer(ModelLayers.STRAY_OUTER_LAYER));
            creeperPoweredModel = new CreeperModel<>(bakery.bakeLayer(ModelLayers.CREEPER_ARMOR));

            cowRoot = net.minecraft.client.model.CowModel.createBodyLayer().bakeRoot();
            sheepRoot = net.minecraft.client.model.SheepModel.createBodyLayer().bakeRoot();
            // SheepFurModel is the inflated wool-overlay layer that vanilla SheepFurLayer
            // renders on top of the sheared body. Children mirror SheepModel exactly
            // (body, head, four legs) so the same setPos values overlay correctly.
            sheepFurRoot = net.minecraft.client.model.SheepFurModel.createFurLayer().bakeRoot();
            pigRoot = net.minecraft.client.model.PigModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
            // Pig saddle — vanilla SaddleLayer uses ModelLayers.PIG_SADDLE which is an
            // inflated copy of the pig's body shape. Same child names as the base pig
            // model so the same setPos calls work for both.
            pigSaddleRoot = bakery.bakeLayer(ModelLayers.PIG_SADDLE);
            chickenRoot = ChickenModel.createBodyLayer().bakeRoot();
            catRoot = bakery.bakeLayer(ModelLayers.CAT);
            catCollarRoot = bakery.bakeLayer(ModelLayers.CAT_COLLAR);
            batRoot = bakery.bakeLayer(ModelLayers.BAT);
            beeRoot = bakery.bakeLayer(ModelLayers.BEE);
            witchRoot = bakery.bakeLayer(ModelLayers.WITCH);
            horseRoot = bakery.bakeLayer(ModelLayers.HORSE);
            donkeyRoot = bakery.bakeLayer(ModelLayers.DONKEY);
            muleRoot = bakery.bakeLayer(ModelLayers.MULE);
            skeletonHorseRoot = bakery.bakeLayer(ModelLayers.SKELETON_HORSE);
            zombieHorseRoot = bakery.bakeLayer(ModelLayers.ZOMBIE_HORSE);
            wolfRoot = bakery.bakeLayer(ModelLayers.WOLF);
            foxRoot = bakery.bakeLayer(ModelLayers.FOX);
            pandaRoot = bakery.bakeLayer(ModelLayers.PANDA);
            ironGolemRoot = bakery.bakeLayer(ModelLayers.IRON_GOLEM);
            goatRoot = bakery.bakeLayer(ModelLayers.GOAT);
            polarBearRoot = bakery.bakeLayer(ModelLayers.POLAR_BEAR);
            turtleRoot = bakery.bakeLayer(ModelLayers.TURTLE);
            endermanRoot = bakery.bakeLayer(ModelLayers.ENDERMAN);
            camelRoot = bakery.bakeLayer(ModelLayers.CAMEL);
            llamaRoot = bakery.bakeLayer(ModelLayers.LLAMA);
            llamaDecorRoot = bakery.bakeLayer(ModelLayers.LLAMA_DECOR);
            rabbitRoot = bakery.bakeLayer(ModelLayers.RABBIT);
            frogRoot = bakery.bakeLayer(ModelLayers.FROG);
            hoglinRoot = bakery.bakeLayer(ModelLayers.HOGLIN);
            snifferRoot = bakery.bakeLayer(ModelLayers.SNIFFER);
            ravagerRoot = bakery.bakeLayer(ModelLayers.RAVAGER);
            phantomRoot = bakery.bakeLayer(ModelLayers.PHANTOM);
            parrotRoot = bakery.bakeLayer(ModelLayers.PARROT);
            slimeInnerRoot = bakery.bakeLayer(ModelLayers.SLIME);
            slimeOuterRoot = bakery.bakeLayer(ModelLayers.SLIME_OUTER);
            magmaCubeRoot = bakery.bakeLayer(ModelLayers.MAGMA_CUBE);
            silverfishRoot = bakery.bakeLayer(ModelLayers.SILVERFISH);
            endermiteRoot = bakery.bakeLayer(ModelLayers.ENDERMITE);
            allayRoot = bakery.bakeLayer(ModelLayers.ALLAY);
            striderRoot = bakery.bakeLayer(ModelLayers.STRIDER);
            striderSaddleRoot = bakery.bakeLayer(ModelLayers.STRIDER_SADDLE);
            snowGolemRoot = bakery.bakeLayer(ModelLayers.SNOW_GOLEM);
            blazeRoot = bakery.bakeLayer(ModelLayers.BLAZE);
            spiderRoot = bakery.bakeLayer(ModelLayers.SPIDER);
            caveSpiderRoot = bakery.bakeLayer(ModelLayers.CAVE_SPIDER);
            shulkerRoot = bakery.bakeLayer(ModelLayers.SHULKER);
            ghastRoot = bakery.bakeLayer(ModelLayers.GHAST);
            vexRoot = bakery.bakeLayer(ModelLayers.VEX);
            wardenRoot = bakery.bakeLayer(ModelLayers.WARDEN);

            mobArmorInner = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            mobArmorOuter = new HumanoidModel<>(bakery.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));

            // Set all model parts visible once — no need to do this every frame
            setAllPartsVisible(normalModel);
            setAllPartsVisible(slimModel);
            setAllPartsVisible(standardHumanoidModel);
            setAllPartsVisible(skeletonModel);
            setAllPartsVisible(drownedModel);
            setAllPartsVisible(drownedOuterModel);
            setAllPartsVisible(strayClothingModel);
            setAllPartsVisible(piglinModel);
            makeAllChildrenVisible(creeperPoweredModel.root());
            makeAllChildrenVisible(cowRoot);
            makeAllChildrenVisible(sheepRoot);
            makeAllChildrenVisible(sheepFurRoot);
            makeAllChildrenVisible(pigRoot);
            makeAllChildrenVisible(pigSaddleRoot);
            makeAllChildrenVisible(chickenRoot);
            makeAllChildrenVisible(catRoot);
            makeAllChildrenVisible(catCollarRoot);
            makeAllChildrenVisible(batRoot);
            makeAllChildrenVisible(beeRoot);
            makeAllChildrenVisible(witchRoot);
            makeAllChildrenVisible(horseRoot);
            makeAllChildrenVisible(donkeyRoot);
            makeAllChildrenVisible(muleRoot);
            makeAllChildrenVisible(skeletonHorseRoot);
            makeAllChildrenVisible(zombieHorseRoot);
            makeAllChildrenVisible(wolfRoot);
            makeAllChildrenVisible(foxRoot);
            makeAllChildrenVisible(pandaRoot);
            makeAllChildrenVisible(ironGolemRoot);
            makeAllChildrenVisible(goatRoot);
            makeAllChildrenVisible(polarBearRoot);
            makeAllChildrenVisible(turtleRoot);
            makeAllChildrenVisible(endermanRoot);
            makeAllChildrenVisible(camelRoot);
            makeAllChildrenVisible(llamaRoot);
            makeAllChildrenVisible(llamaDecorRoot);
            makeAllChildrenVisible(rabbitRoot);
            makeAllChildrenVisible(frogRoot);
            makeAllChildrenVisible(hoglinRoot);
            makeAllChildrenVisible(snifferRoot);
            makeAllChildrenVisible(ravagerRoot);
            makeAllChildrenVisible(phantomRoot);
            makeAllChildrenVisible(parrotRoot);
            makeAllChildrenVisible(slimeInnerRoot);
            makeAllChildrenVisible(slimeOuterRoot);
            makeAllChildrenVisible(magmaCubeRoot);
            makeAllChildrenVisible(silverfishRoot);makeAllChildrenVisible(endermiteRoot);
            makeAllChildrenVisible(allayRoot);makeAllChildrenVisible(striderRoot);makeAllChildrenVisible(striderSaddleRoot);
            makeAllChildrenVisible(snowGolemRoot);makeAllChildrenVisible(blazeRoot);
            makeAllChildrenVisible(spiderRoot);makeAllChildrenVisible(caveSpiderRoot);
            makeAllChildrenVisible(shulkerRoot);makeAllChildrenVisible(ghastRoot);
            makeAllChildrenVisible(vexRoot);makeAllChildrenVisible(wardenRoot);

            initialized = true;
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Failed to initialize ClientRagdollRenderer models", e);
        }
    }

    private static HumanoidModel<AbstractClientPlayer> freshPlayerArmor(float deformation) {
        LayerDefinition definition = LayerDefinition.create(
                HumanoidModel.createMesh(new CubeDeformation(deformation), 0.0F), 64, 32);
        return new HumanoidModel<>(definition.bakeRoot());
    }

    private static void setAllPartsVisible(HumanoidModel<?> model) {
        makeAllChildrenVisible(model.head);
        makeAllChildrenVisible(model.body);
        makeAllChildrenVisible(model.leftArm);
        makeAllChildrenVisible(model.rightArm);
        makeAllChildrenVisible(model.leftLeg);
        makeAllChildrenVisible(model.rightLeg);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        // Free wound textures released since the last frame (render thread only). Ahead of the
        // no-ragdolls early-out on purpose: corpses release textures too, and a world holding
        // only corpses would otherwise never drain the queue.
        com.raiiiden.ragdollified.client.compat.BetterBloodOverlayCompat.releasePending();

        var ragdolls = ClientRagdollManager.getAll();
        if (ragdolls.isEmpty()) {
            recordRenderFrame(0, 0, 0);
            return;
        }

        initModels();
        if (!initialized) return;

        long renderStart = System.nanoTime();

        PoseStack poseStack = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        float partialTick = event.getPartialTick();

        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();

        int rendered = 0;
        int culled = 0;
        for (ClientRagdoll ragdoll : ragdolls) {
            // Grab the snapshot once per ragdoll. Everything we render off this
            // ragdoll uses the same snapshot for consistency — even if physics
            // publishes a new one mid-frame, our render stays coherent.
            ClientRagdoll.TransformSnapshot snap = ragdoll.getSnapshot();
            if (snap == null || snap.destroyed) continue;

            // Update render-side EMA smoothing for this frame. Damps physics jitter
            // (oscillation under contact pressure) without affecting real motion. An attached
            // camera already prepared its ragdoll at camera-setup time with this same token,
            // so this call cannot advance it a second time or pull a newer mid-frame snapshot.
            ragdoll.updateSmoothedRenderState(
                    snap, partialTick, ClientRagdollCamera.currentRenderFrame());

            // Distance culling — 48 blocks. Use the smoothed torso pos so the cull
            // boundary itself doesn't jitter (caused pop-in artifacts at the edge).
            Vector3f torsoPos = ragdoll.getSmoothedTorsoPos();
            double distSq = camPos.distanceToSqr(torsoPos.x, torsoPos.y, torsoPos.z);
            double renderDistance = RagdollifiedConfig.RENDER_DISTANCE.get();
            if (distSq > renderDistance * renderDistance) {
                culled++;
                continue;
            }

            int light = getLightLevel(torsoPos);

            poseStack.pushPose();
            try {
                poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

                if (ragdoll.isPlayer()) {
                    renderPlayerRagdoll(ragdoll, snap, poseStack, buffer, light, partialTick, distSq);
                } else {
                    renderMobRagdoll(ragdoll, snap, poseStack, buffer, light, partialTick, distSq);
                }
                rendered++;
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Error rendering ragdoll {}", ragdoll.getId(), e);
            } finally {
                poseStack.popPose();
            }
        }

        buffer.endBatch();

        recordRenderFrame(System.nanoTime() - renderStart, rendered, culled);
    }

    private static void recordRenderFrame(long nanos, int rendered, int culled) {
        lastRenderFrameNanos = nanos;
        lastRenderedCount = rendered;
        lastCulledCount = culled;
        renderFrameRing[renderFrameRingIdx] = nanos;
        renderFrameRingIdx = (renderFrameRingIdx + 1) % renderFrameRing.length;
        if (renderFrameRingFilled < renderFrameRing.length) renderFrameRingFilled++;
    }

    private static int getLightLevel(Vector3f pos) {
        // Sample light from slightly above the torso to avoid sampling inside the block
        // the ragdoll is resting on (which would return darkness/black overlay)
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y + 0.5f);
        int z = (int) Math.floor(pos.z);
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(x, y, z);
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return 15728880; // full bright fallback
        // If the sampled position is inside a solid block, try one block above
        if (level.getBlockState(blockPos).isSolidRender(level, blockPos)) {
            blockPos = blockPos.above();
        }
        return net.minecraft.client.renderer.LevelRenderer.getLightColor(level, blockPos);
    }

    // ============================
    // Player ragdoll rendering
    // ============================

    // Y offset for ragdolls settled on a fluid surface. The body is frozen out of the dynamics
    // world, so one floating on water would be perfectly static; a sin-driven bob at render time
    // fakes surface motion without re-running physics. Phase varies per ragdoll so a row of
    // corpses on a pond does not bob in lockstep.
    private static float liquidBobOffset(ClientRagdoll ragdoll) {
        if (!ragdoll.isSettledOnLiquid()) return 0f;
        long now = System.currentTimeMillis();
        float phase = (ragdoll.getId() & 0xFF) * 0.0246f; // ~0..2π spread across 256 ids
        return (float) (Math.sin(now * 0.0044 + phase) * 0.06f); // ~0.7 Hz, ±0.06 blocks
    }

    private static void renderPlayerRagdoll(ClientRagdoll ragdoll, ClientRagdoll.TransformSnapshot snap,
                                            PoseStack poseStack,
                                            MultiBufferSource buffer, int light, float partialTick, double distSq) {
        UUID uuid = ragdoll.getPlayerUUID();
        ResourceLocation skin = ragdoll.getCachedPlayerSkin();
        boolean isSlim = ragdoll.isCachedSlim();
        if (skin == null) {
            skin = uuid != null
                    ? DefaultPlayerSkin.getDefaultSkin(uuid)
                    : DefaultPlayerSkin.getDefaultSkin();
        }
        // May be null if the player has despawned or moved out of render distance.
        // Vanilla armor is cached on the ragdoll so it renders regardless.
        // GeckoLib armor falls back to its internal proxy ArmorStand when null,
        // same as the mob path — so armor stays visible at any distance.
        AbstractClientPlayer playerEntity = findPlayerEntity(uuid);

        RagdollTransform torso = ragdoll.getSmoothedTransform(RagdollPart.TORSO);
        RagdollTransform head  = ragdoll.getSmoothedTransform(RagdollPart.HEAD);
        RagdollTransform larm  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_ARM);
        RagdollTransform rarm  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_ARM);
        RagdollTransform lleg  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_LEG);
        RagdollTransform rleg  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_LEG);

        // Looking out of this head means we are sitting inside it, so the skull, hat layer, and
        // helmet all render as inside-out geometry across the view. Dropping the transform is
        // what skips them: every head-driven pass below already bails on a null. This is a local
        // draw decision only — other clients render this same ragdoll with its head intact.
        if (ClientRagdollCamera.isHeadHidden(ragdoll.getId())) head = null;

        renderPlayerBody(poseStack, buffer, light, distSq, torso, head, larm, rarm, lleg, rleg,
                skin, isSlim,
                ragdoll.getHelmet(), ragdoll.getChestplate(), ragdoll.getLeggings(), ragdoll.getBoots(),
                playerEntity, liquidBobOffset(ragdoll), ragdoll.getOriginalEntityId(),
                CuriosRenderCompat.wornFor(ragdoll.getOriginalEntityId()));
    }

    // Render a humanoid body and armor at the given part transforms, shared by the live ragdoll
    // path and the corpse renderer. The poseStack must already be camera-relative; this
    // translates to the torso and draws each part at its torso-relative position with absolute
    // rotation. distSq=0 always renders armor, skin must be non-null, and damageKey identifies the
    // body in the damage-visual compats — the ragdoll's source entity id for a live ragdoll, the
    // corpse entity's UUID for a corpse, or null to draw a clean body.
    static void renderPlayerBody(PoseStack poseStack, MultiBufferSource buffer, int light, double distSq,
                                 RagdollTransform torso, RagdollTransform head,
                                 RagdollTransform larm, RagdollTransform rarm,
                                 RagdollTransform lleg, RagdollTransform rleg,
                                 ResourceLocation skin, boolean isSlim,
                                 ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                                 AbstractClientPlayer playerEntity, float bob, Object damageKey,
                                 java.util.List<CuriosCompat.WornCurio> curios) {
        if (torso == null) return;
        // The corpse renderer calls this directly and can run before any live ragdoll has — e.g. a
        // corpse loaded from disk on world (re)load with no ragdoll around, in which case
        // onRenderLevel's lazy init never fired. Bake the models here too, and bail if they still
        // aren't ready, so we never dereference a null model (was: NPE spam + invisible corpse).
        initModels();
        PlayerModel<AbstractClientPlayer> model = isSlim ? slimModel : normalModel;
        if (model == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(torso.position.x, torso.position.y + bob, torso.position.z);

            // Visual Health composites its wounds into the skin itself rather than drawing a
            // layer, so swapping the texture here covers the base body and the second skin layer
            // at once. No-op without Visual Health, or when this body died undamaged.
            VertexConsumer vc = buffer.getBuffer(
                    RenderType.entityTranslucent(VisualHealthCompat.texture(damageKey, skin)));

            renderHumanoidPartPhysics(poseStack, vc, model.body,     torso, torso, light, RagdollPart.TORSO);
            renderHumanoidPartPhysics(poseStack, vc, model.head,     head,  torso, light, RagdollPart.HEAD);
            renderHumanoidPartPhysics(poseStack, vc, model.leftLeg,  lleg,  torso, light, RagdollPart.LEFT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.rightLeg, rleg,  torso, light, RagdollPart.RIGHT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.leftArm,  larm,  torso, light, RagdollPart.LEFT_ARM);
            renderHumanoidPartPhysics(poseStack, vc, model.rightArm, rarm,  torso, light, RagdollPart.RIGHT_ARM);

            // Second skin layer (hat / jacket / sleeves / pants). PlayerModel keeps these as
            // separate sibling parts, so the base-part passes above don't draw them; render
            // each over its matching base part so the player's outer skin layer shows.
            renderHumanoidPartPhysics(poseStack, vc, model.hat,         head,  torso, light, RagdollPart.HEAD);
            renderHumanoidPartPhysics(poseStack, vc, model.jacket,      torso, torso, light, RagdollPart.TORSO);
            renderHumanoidPartPhysics(poseStack, vc, model.leftPants,   lleg,  torso, light, RagdollPart.LEFT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.rightPants,  rleg,  torso, light, RagdollPart.RIGHT_LEG);
            renderHumanoidPartPhysics(poseStack, vc, model.leftSleeve,  larm,  torso, light, RagdollPart.LEFT_ARM);
            renderHumanoidPartPhysics(poseStack, vc, model.rightSleeve, rarm,  torso, light, RagdollPart.RIGHT_ARM);

            // Procedural blood carried over from the live player (under armor). No-op unless
            // Better Blood Overlay is installed and the player was bleeding at death.
            if (damageKey != null) {
                renderHumanoidBlood(damageKey, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, model, HumanoidScale.ADULT);
            }

            double armorDistSq = RagdollifiedConfig.getArmorRenderDistanceSq();
            double geckoDistSq = RagdollifiedConfig.getGeckoLibArmorRenderDistanceSq();

            if (distSq <= armorDistSq) {
                renderPlayerVanillaArmor(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim, playerEntity, helmet, chestplate, leggings, boots);

                if (distSq <= geckoDistSq) {
                    // Pass playerEntity which may be null — GeckoLibArmorHelper uses its
                    // internal proxy ArmorStand as fallback, identical to the mob armor path.
                    renderPlayerGeckoLibArmor(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, isSlim, playerEntity, helmet, chestplate, leggings, boots);
                }

                // Curios last, over the armor, matching the order Curios' own render layer runs
                // in. Shares the armor render distance — a curio is the same kind of detail.
                renderBodyCurios(curios, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg,
                        playerEntity, skin, model);
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Error rendering player body", e);
        } finally {
            poseStack.popPose();
        }
    }

    // ============================
    // Curios
    // ============================

    // Pivot each part is given in its own physics frame by renderHumanoidPartPhysics. Curios are
    // placed by reproducing those exact pivots relative to a single model root, so the two passes
    // can never disagree about where a limb is.
    private static final org.joml.Vector3f[] PART_PIVOTS = new org.joml.Vector3f[RagdollTransform.MAX_PARTS];
    static {
        PART_PIVOTS[RagdollPart.HEAD.index]      = new org.joml.Vector3f(0f, 4f, 0f);
        PART_PIVOTS[RagdollPart.TORSO.index]     = new org.joml.Vector3f(0f, -6f, 0f);
        PART_PIVOTS[RagdollPart.LEFT_ARM.index]  = new org.joml.Vector3f(-1f, -4f, 0f);
        PART_PIVOTS[RagdollPart.RIGHT_ARM.index] = new org.joml.Vector3f(1f, -4f, 0f);
        PART_PIVOTS[RagdollPart.LEFT_LEG.index]  = new org.joml.Vector3f(0f, -6f, 0f);
        PART_PIVOTS[RagdollPart.RIGHT_LEG.index] = new org.joml.Vector3f(0f, -6f, 0f);
    }

    // Draw the curios a body was wearing when it died.
    //
    // Curio renderers are written against a live entity: they position themselves off the wearer's
    // HumanoidModel, usually by calling ICurioRenderer.followBodyRotations/followHeadRotations,
    // which copy pivots and rotations out of the model held by that entity's *renderer*. So rather
    // than trying to anchor each curio to a body part ourselves — we have no idea which part any
    // given curio wants — we pose that model to the physics pose and let each renderer place
    // itself exactly as it would on a living player.
    private static void renderBodyCurios(java.util.List<CuriosCompat.WornCurio> curios,
                                         PoseStack poseStack, MultiBufferSource buffer, int light,
                                         RagdollTransform torso, RagdollTransform head,
                                         RagdollTransform larm, RagdollTransform rarm,
                                         RagdollTransform lleg, RagdollTransform rleg,
                                         AbstractClientPlayer playerEntity, ResourceLocation skin,
                                         PlayerModel<AbstractClientPlayer> bodyModel) {
        // No availability gate here: the two draw paths below check their own mods, and the
        // GeckoLib one still works when the ICurioRenderer registry failed to resolve.
        if (curios == null || curios.isEmpty() || torso == null) return;

        // Curios needs a LivingEntity for the slot context and for the follow* helpers. The real
        // player is gone once they respawn or leave range, so fall back to the same proxy
        // ArmorStand the armor paths use.
        net.minecraft.world.entity.LivingEntity wearer = playerEntity != null ? playerEntity : GeckoLibArmorHelper.getProxyEntity();
        if (wearer == null) return;

        // Two models have to carry the physics pose, because curio renderers reach for it two
        // different ways:
        //
        //  - follow: whatever model the wearer's own renderer holds, because that is what
        //    ICurioRenderer.followBodyRotations/followHeadRotations copy out of. On the proxy
        //    ArmorStand fallback this is an ArmorStandModel.
        //  - bodyModel: the PlayerModel this body was actually drawn with, handed out as the
        //    RenderLayerParent's model. It must be a PlayerModel and not the proxy's
        //    ArmorStandModel — renderers cast what getModel() returns, and GeckoLib-backed ones
        //    pass it straight into GeoArmorRenderer.prepForRender as the base model to align
        //    their bones against. An armor stand's model there is a bad cast or a bad alignment.
        HumanoidModel<?> follow = liveHumanoidModelFor(wearer);
        BorrowedPose savedFollow = follow != null ? savePose(follow) : null;

        poseStack.pushPose();
        try {
            // Move to the model root: the torso frame, shifted by the torso's own pivot so that a
            // part left at (0,0,0) with no rotation lands exactly where the torso pass drew it.
            tempQuat.set(torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            poseStack.translate(0f, PART_PIVOTS[RagdollPart.TORSO.index].y / 16f, 0f);

            if (follow != null) poseHumanoidFromPhysics(follow, torso, head, larm, rarm, lleg, rleg);
            poseHumanoidFromPhysics(bodyModel, torso, head, larm, rarm, lleg, rleg);

            CuriosRenderCompat.Parent parent = new CuriosRenderCompat.Parent(bodyModel, skin);
            float partialTick = Minecraft.getInstance().getPartialTick();
            float ageInTicks = wearer.tickCount + partialTick;
            for (CuriosCompat.WornCurio worn : curios) {
                if (CuriosRenderCompat.render(worn, wearer, poseStack, buffer, light, parent, partialTick, ageInTicks)) {
                    continue;
                }
                renderGeckoLibCurio(worn.stack(), poseStack, buffer, light, wearer, bodyModel);
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Error rendering curios", e);
        } finally {
            poseStack.popPose();
            // Only the borrowed model needs restoring; bodyModel is ours and is re-posed from
            // scratch every frame.
            if (follow != null) restorePose(follow, savedFollow);
        }
    }

    // Second way of drawing a worn curio, for the many GeckoLib-backed ones that never register an
    // ICurioRenderer at all.
    //
    // Mods in this family (Fracture Point's backpacks are the reference case) add their own
    // RenderLayer to the player renderer instead: it asks Curios which slots are filled, builds a
    // GeoArmorRenderer for the item, copies the player model's pose into it, calls prepForRender,
    // and draws. CuriosRendererRegistry knows nothing about any of it, which is exactly why these
    // items came out invisible on a body. A ragdoll has no render layers, so we do the same work.
    //
    // The pose stack must already be at the model root with baseModel posed to the physics pose:
    // GeoArmorRenderer.prepForRender copies each base part's pivot AND rotation onto the matching
    // bone, so one pass places every bone on the right limb — no per-part pass needed.
    private static void renderGeckoLibCurio(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer,
                                            int light, net.minecraft.world.entity.LivingEntity wearer,
                                            HumanoidModel<?> baseModel) {
        if (stack.isEmpty() || !GeckoLibArmorHelper.isGeckoLibArmor(stack.getItem())) return;

        // The slot decides which bones GeoArmorRenderer makes visible. Curio items are usually
        // ArmorItems anyway (Fracture Point's backpack is a chestplate), so ask the item; anything
        // else is treated as a torso attachment, which is where body-worn curios live.
        EquipmentSlot slot = stack.getItem() instanceof ArmorItem armor
                ? armor.getEquipmentSlot() : EquipmentSlot.CHEST;

        poseStack.pushPose();
        try {
            GeckoLibArmorHelper.renderGeckoLibArmor(stack, slot, wearer, poseStack, buffer, light,
                    OverlayTexture.NO_OVERLAY, baseModel);
        } finally {
            poseStack.popPose();
        }
    }

    // Write the whole physics pose into one humanoid model, in the ragdoll's model-root frame.
    private static void poseHumanoidFromPhysics(HumanoidModel<?> model,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg) {
        model.young = false;
        model.crouching = false;
        model.riding = false;
        poseFromPhysics(model.head, head, torso, RagdollPart.HEAD);
        poseFromPhysics(model.hat, head, torso, RagdollPart.HEAD);
        poseFromPhysics(model.body, torso, torso, RagdollPart.TORSO);
        poseFromPhysics(model.leftArm, larm, torso, RagdollPart.LEFT_ARM);
        poseFromPhysics(model.rightArm, rarm, torso, RagdollPart.RIGHT_ARM);
        poseFromPhysics(model.leftLeg, lleg, torso, RagdollPart.LEFT_LEG);
        poseFromPhysics(model.rightLeg, rleg, torso, RagdollPart.RIGHT_LEG);
    }

    // The HumanoidModel that ICurioRenderer's follow* helpers will read for this wearer — the one
    // its renderer holds, not one of ours. Null when the entity does not render on a humanoid.
    private static HumanoidModel<?> liveHumanoidModelFor(net.minecraft.world.entity.LivingEntity wearer) {
        try {
            var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(wearer);
            if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> ler
                    && ler.getModel() instanceof HumanoidModel<?> humanoid) {
                return humanoid;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // Express one physics part as a pivot and rotation in the model root frame, which is what
    // ModelPart.translateAndRotate — and ModelPart.copyFrom, used by the follow* helpers —
    // consume. Inverts exactly the transform chain renderHumanoidPartPhysics applies, so the
    // resulting pivot sits on the drawn limb.
    private static void poseFromPhysics(ModelPart part, RagdollTransform transform,
                                        RagdollTransform torso, RagdollPart ragdollPart) {
        if (part == null) return;
        if (transform == null) {
            // A hidden part (first-person head) has no transform; park it at the root rather than
            // leaving whatever the last live render left behind.
            part.setPos(0f, 0f, 0f);
            part.xRot = part.yRot = part.zRot = 0f;
            return;
        }

        org.joml.Quaternionf flip = new org.joml.Quaternionf().rotateZ((float) Math.PI);
        org.joml.Quaternionf rootRot = new org.joml.Quaternionf(
                torso.rotation.x, torso.rotation.y, torso.rotation.z, torso.rotation.w).mul(flip);
        org.joml.Quaternionf partRot = new org.joml.Quaternionf(
                transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w).mul(flip);

        // Root origin in world: the torso centre displaced by the torso's own pivot.
        org.joml.Vector3f torsoPivot = new org.joml.Vector3f(PART_PIVOTS[RagdollPart.TORSO.index]).div(16f);
        rootRot.transform(torsoPivot);
        org.joml.Vector3f root = new org.joml.Vector3f(
                torso.position.x + torsoPivot.x,
                torso.position.y + torsoPivot.y,
                torso.position.z + torsoPivot.z);

        // This part's pivot in world: its own centre displaced by its pivot, in its own frame.
        org.joml.Vector3f pivot = new org.joml.Vector3f(PART_PIVOTS[ragdollPart.index]).div(16f);
        partRot.transform(pivot);
        pivot.add(transform.position.x, transform.position.y, transform.position.z).sub(root);

        // Into the root frame, back to model units.
        new org.joml.Quaternionf(rootRot).conjugate().transform(pivot);
        part.setPos(pivot.x * 16f, pivot.y * 16f, pivot.z * 16f);

        // ModelPart.translateAndRotate rotates Z, then Y, then X — the ZYX sequence.
        org.joml.Vector3f euler = new org.joml.Quaternionf(rootRot).conjugate().mul(partRot)
                .getEulerAnglesZYX(new org.joml.Vector3f());
        part.xRot = euler.x;
        part.yRot = euler.y;
        part.zRot = euler.z;
    }

    // Everything renderBodyCurios writes on the borrowed model — the pivots and rotations of the
    // seven parts copyPropertiesTo touches, plus the flags — so one curio pass cannot leak into
    // how that entity draws next frame.
    private record BorrowedPose(float[] parts, boolean young, boolean riding, boolean crouching) {}

    private static ModelPart[] borrowedParts(HumanoidModel<?> model) {
        return new ModelPart[]{model.head, model.hat, model.body,
                model.leftArm, model.rightArm, model.leftLeg, model.rightLeg};
    }

    private static BorrowedPose savePose(HumanoidModel<?> model) {
        ModelPart[] parts = borrowedParts(model);
        float[] saved = new float[parts.length * 6];
        for (int i = 0; i < parts.length; i++) {
            int o = i * 6;
            saved[o] = parts[i].x;        saved[o + 1] = parts[i].y;      saved[o + 2] = parts[i].z;
            saved[o + 3] = parts[i].xRot; saved[o + 4] = parts[i].yRot;   saved[o + 5] = parts[i].zRot;
        }
        return new BorrowedPose(saved, model.young, model.riding, model.crouching);
    }

    private static void restorePose(HumanoidModel<?> model, BorrowedPose saved) {
        ModelPart[] parts = borrowedParts(model);
        for (int i = 0; i < parts.length; i++) {
            int o = i * 6;
            parts[i].setPos(saved.parts()[o], saved.parts()[o + 1], saved.parts()[o + 2]);
            parts[i].xRot = saved.parts()[o + 3];
            parts[i].yRot = saved.parts()[o + 4];
            parts[i].zRot = saved.parts()[o + 5];
        }
        model.young = saved.young();
        model.riding = saved.riding();
        model.crouching = saved.crouching();
    }

    private static void renderPlayerVanillaArmor(PoseStack poseStack, MultiBufferSource buffer,
                                                  int light, RagdollTransform torso, RagdollTransform head,
                                                  RagdollTransform larm, RagdollTransform rarm,
                                                  RagdollTransform lleg, RagdollTransform rleg, boolean isSlim,
                                                  net.minecraft.world.entity.LivingEntity entity,
                                                  ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        HumanoidModel<AbstractClientPlayer> innerModel = isSlim ? slimArmorInner : normalArmorInner;
        HumanoidModel<AbstractClientPlayer> outerModel = isSlim ? slimArmorOuter : normalArmorOuter;

        renderVanillaArmorSlot(helmet, EquipmentSlot.HEAD, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
        renderVanillaArmorSlot(chestplate, EquipmentSlot.CHEST, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
        renderVanillaArmorSlot(leggings, EquipmentSlot.LEGS, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
        renderVanillaArmorSlot(boots, EquipmentSlot.FEET, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, innerModel, outerModel, entity);
    }

    private static void renderVanillaArmorSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                                MultiBufferSource buffer, int light,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg,
                                                HumanoidModel<?> innerModel, HumanoidModel<?> outerModel,
                                                net.minecraft.world.entity.LivingEntity entity) {
        renderVanillaArmorSlot(stack, slot, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg,
                innerModel, outerModel, entity, HumanoidScale.ADULT);
    }

    private static void renderVanillaArmorSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                                MultiBufferSource buffer, int light,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg,
                                                HumanoidModel<?> innerModel, HumanoidModel<?> outerModel,
                                                net.minecraft.world.entity.LivingEntity entity, HumanoidScale modelScale) {
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        if (GeckoLibArmorHelper.isGeckoLibArmor(item)) return; // handled separately
        if (!(item instanceof ArmorItem armorItem)) return;

        // Mirror vanilla HumanoidArmorLayer: legs use the inner (less-inflated) model so
        // leggings sit under the chestplate; everything else uses the outer model.
        HumanoidModel<?> base = (slot == EquipmentSlot.LEGS) ? innerModel : outerModel;
        // Resolve the actual model to render. Modded (non-GeckoLib) armor ships textures
        // UV-mapped for its own custom armor model, exposed via the Forge
        // IClientItemExtensions.getHumanoidArmorModel hook. Rendering its texture on the
        // vanilla model is what made modded armor map to the wrong faces — so we ask the
        // item for its model here, exactly like vanilla's HumanoidArmorLayer does.
        HumanoidModel<?> model = resolveArmorModel(stack, slot, entity, base);

        // Leather (and any DyeableLeatherItem) renders in two passes, like vanilla:
        // a base layer tinted by the dye color, then an untinted overlay (straps/buckles).
        // Skipping the dye tint is what made undyed leather render as flat grey — i.e.
        // look like iron/chainmail. Non-dyeable armor uses a single untinted pass.
        boolean dyeable = item instanceof net.minecraft.world.item.DyeableLeatherItem;
        float r = 1f, g = 1f, b = 1f;
        if (dyeable) {
            int color = ((net.minecraft.world.item.DyeableLeatherItem) item).getColor(stack);
            r = (color >> 16 & 255) / 255f;
            g = (color >> 8 & 255) / 255f;
            b = (color & 255) / 255f;
        }

        ResourceLocation baseTex = getArmorTexture(armorItem, slot, stack, entity, null);
        renderArmorSlotParts(model, baseTex, slot, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, r, g, b, modelScale);

        if (dyeable) {
            ResourceLocation overlayTex = getArmorTexture(armorItem, slot, stack, entity, "overlay");
            renderArmorSlotParts(model, overlayTex, slot, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, 1f, 1f, 1f, modelScale);
        }
    }

    // Render the body parts relevant to one armor slot from model, tinted r,g,b.
    private static void renderArmorSlotParts(HumanoidModel<?> model, ResourceLocation texture, EquipmentSlot slot,
                                             PoseStack poseStack, MultiBufferSource buffer, int light,
                                             RagdollTransform torso, RagdollTransform head,
                                             RagdollTransform larm, RagdollTransform rarm,
                                             RagdollTransform lleg, RagdollTransform rleg,
                                             float r, float g, float b, HumanoidScale modelScale) {
        VertexConsumer vc = buffer.getBuffer(RenderType.armorCutoutNoCull(texture));
        switch (slot) {
            case HEAD:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.head, head, torso, light, RagdollPart.HEAD, r, g, b, 1f, modelScale);
                break;
            case CHEST:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.leftArm, larm, torso, light, RagdollPart.LEFT_ARM, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM, r, g, b, 1f, modelScale);
                break;
            case LEGS:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, r, g, b, 1f, modelScale);
                break;
            case FEET:
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, r, g, b, 1f, modelScale);
                renderHumanoidPartPhysicsTinted(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, r, g, b, 1f, modelScale);
                break;
        }
    }

    // Ask the armor item which model it wants, via Forge's getHumanoidArmorModel. Vanilla armor
    // returns base unchanged; modded armor returns its own so its texture UVs line up. Any error
    // falls back to base.
    private static HumanoidModel<?> resolveArmorModel(ItemStack stack, EquipmentSlot slot,
                                                      net.minecraft.world.entity.LivingEntity entity,
                                                      HumanoidModel<?> base) {
        try {
            // Most mods key off a non-null LivingEntity; reuse GeckoLib's proxy ArmorStand
            // when we don't have the real one (despawned player / mob path).
            net.minecraft.world.entity.LivingEntity e = entity != null ? entity : GeckoLibArmorHelper.getProxyEntity();
            HumanoidModel<?> custom = net.minecraftforge.client.extensions.common.IClientItemExtensions.of(stack)
                    .getHumanoidArmorModel(e, stack, slot, base);
            if (custom != null && custom != base) {
                // We drive part pose/visibility ourselves, so make sure the custom model's
                // parts are all visible before we pick the ones this slot needs.
                setAllPartsVisible(custom);
                return custom;
            }
        } catch (Exception ex) {
            Ragdollified.LOGGER.debug("Custom armor model lookup failed, using vanilla model: {}", ex.getMessage());
        }
        return base;
    }

    private static void renderPlayerGeckoLibArmor(PoseStack poseStack, MultiBufferSource buffer,
                                                  int light, RagdollTransform torso, RagdollTransform head,
                                                  RagdollTransform larm, RagdollTransform rarm,
                                                  RagdollTransform lleg, RagdollTransform rleg, boolean isSlim,
                                                  AbstractClientPlayer playerEntity,
                                                  ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        // playerEntity may be null if the player has despawned — GeckoLibArmorHelper
        // falls back to its internal proxy ArmorStand in that case, same as mobs.
        HumanoidModel<AbstractClientPlayer> baseModel = isSlim ? slimArmorInner : normalArmorInner;

        renderGeckoLibSlot(helmet,     EquipmentSlot.HEAD,  poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(chestplate, EquipmentSlot.CHEST, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(leggings,   EquipmentSlot.LEGS,  poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
        renderGeckoLibSlot(boots,      EquipmentSlot.FEET,  poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, playerEntity);
    }

    private static AbstractClientPlayer findPlayerEntity(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p.getUUID().equals(uuid)) return p;
            }
        }
        return null;
    }

    private static void renderGeckoLibSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                            MultiBufferSource buffer, int light, HumanoidModel<?> baseModel,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg,
                                            net.minecraft.world.entity.LivingEntity entity) {
        renderGeckoLibSlot(stack, slot, poseStack, buffer, light, baseModel, torso, head, larm, rarm, lleg, rleg, entity, HumanoidScale.ADULT);
    }

    private static void renderGeckoLibSlot(ItemStack stack, EquipmentSlot slot, PoseStack poseStack,
                                            MultiBufferSource buffer, int light, HumanoidModel<?> baseModel,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg,
                                            net.minecraft.world.entity.LivingEntity entity, HumanoidScale modelScale) {
        if (stack.isEmpty()) return;
        if (!GeckoLibArmorHelper.isGeckoLibArmor(stack.getItem())) return;

        baseModel.setAllVisible(false);
        baseModel.young = false;
        baseModel.crouching = false;
        baseModel.riding = false;

        switch (slot) {
            case HEAD:
                if (head != null) {
                    baseModel.head.visible = true;
                    resetPart(baseModel.head);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, head, torso, RagdollPart.HEAD, entity, modelScale);
                    baseModel.head.visible = false;
                }
                break;
            case CHEST:
                if (torso != null && larm != null && rarm != null) {
                    baseModel.body.visible = true;
                    resetPart(baseModel.body);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO, entity, modelScale);
                    baseModel.body.visible = false;

                    baseModel.leftArm.visible = true;
                    resetPart(baseModel.leftArm);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, larm, torso, RagdollPart.LEFT_ARM, entity, modelScale);
                    baseModel.leftArm.visible = false;

                    baseModel.rightArm.visible = true;
                    resetPart(baseModel.rightArm);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rarm, torso, RagdollPart.RIGHT_ARM, entity, modelScale);
                    baseModel.rightArm.visible = false;
                }
                break;
            case LEGS:
                if (torso != null && lleg != null && rleg != null) {
                    baseModel.body.visible = true;
                    resetPart(baseModel.body);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, torso, torso, RagdollPart.TORSO, entity, modelScale);
                    baseModel.body.visible = false;

                    baseModel.leftLeg.visible = true;
                    resetPart(baseModel.leftLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG, entity, modelScale);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    resetPart(baseModel.rightLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG, entity, modelScale);
                    baseModel.rightLeg.visible = false;
                }
                break;
            case FEET:
                if (lleg != null && rleg != null) {
                    baseModel.leftLeg.visible = true;
                    resetPart(baseModel.leftLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, lleg, torso, RagdollPart.LEFT_LEG, entity, modelScale);
                    baseModel.leftLeg.visible = false;

                    baseModel.rightLeg.visible = true;
                    resetPart(baseModel.rightLeg);
                    renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, rleg, torso, RagdollPart.RIGHT_LEG, entity, modelScale);
                    baseModel.rightLeg.visible = false;
                }
                break;
        }
    }

    private static void renderGeckoLibPartPhysics(ItemStack stack, EquipmentSlot slot,
                                                   PoseStack poseStack, MultiBufferSource buffer, int light,
                                                   HumanoidModel<?> baseModel,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   RagdollPart ragdollPart,
                                                   net.minecraft.world.entity.LivingEntity entity) {
        renderGeckoLibPartPhysics(stack, slot, poseStack, buffer, light, baseModel, transform, torso, ragdollPart, entity, HumanoidScale.ADULT);
    }

    private static void renderGeckoLibPartPhysics(ItemStack stack, EquipmentSlot slot,
                                                   PoseStack poseStack, MultiBufferSource buffer, int light,
                                                   HumanoidModel<?> baseModel,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   RagdollPart ragdollPart,
                                                   net.minecraft.world.entity.LivingEntity entity, HumanoidScale scale) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

            float centerX = 0, centerY = 0, centerZ = 0;
            switch (ragdollPart) {
                case HEAD:      centerY = 4f / 16f;   break;
                case TORSO:     centerY = -6f / 16f;   break;
                case LEFT_ARM:  centerX = -1f / 16f; centerY = -4f / 16f; break;
                case RIGHT_ARM: centerX = 1f / 16f;  centerY = -4f / 16f; break;
                case LEFT_LEG:
                case RIGHT_LEG: centerY = -6f / 16f;   break;
            }
            poseStack.translate(centerX, centerY, centerZ);

            GeckoLibArmorHelper.renderGeckoLibArmor(stack, slot, entity, poseStack, buffer, light, OverlayTexture.NO_OVERLAY, baseModel);
        } finally {
            poseStack.popPose();
        }
    }

    // ============================
    // Mob ragdoll rendering
    // ============================

    private static void renderMobRagdoll(ClientRagdoll ragdoll, ClientRagdoll.TransformSnapshot snap,
                                         PoseStack poseStack,
                                         MultiBufferSource buffer, int light, float partialTick, double distSq) {
        MobModelHelper.ModelType modelType = ragdoll.getModelType();

        RagdollTransform torso = ragdoll.getSmoothedTransform(RagdollPart.TORSO);
        RagdollTransform head  = ragdoll.getSmoothedTransform(RagdollPart.HEAD);
        RagdollTransform larm  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_ARM);
        RagdollTransform rarm  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_ARM);
        RagdollTransform lleg  = ragdoll.getSmoothedTransform(RagdollPart.LEFT_LEG);
        RagdollTransform rleg  = ragdoll.getSmoothedTransform(RagdollPart.RIGHT_LEG);

        if (torso == null) return;

        poseStack.pushPose();
        try {
            float bob = liquidBobOffset(ragdoll);
            poseStack.translate(torso.position.x, torso.position.y + bob, torso.position.z);

            ResourceLocation texture = getMobTexture(ragdoll);
            VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

            // Baby humanoids get their model + armor scaled to match the physics bodies built
            // in RagdollBodyFactory.buildHumanoid. Whether the head is enlarged is decided
            // per-mob to mirror vanilla (see ClientRagdoll#babyScalesHead) — big head for
            // zombies/piglins/zombie-villagers, uniform shrink for plain villagers.
            HumanoidScale humanoidScale = !ragdoll.isBabyHumanoid() ? HumanoidScale.ADULT
                    : (ragdoll.babyScalesHead() ? HumanoidScale.BABY : HumanoidScale.BABY_UNIFORM);

            // Better Blood Overlay: set to the HumanoidModel actually drawn (it shares vanilla
            // UVs, which BBO's wound atlas is authored against), or flag the villager/illager
            // paths which render on their own (non-HumanoidModel) trees. All left unset for
            // non-humanoid families BBO doesn't cover (creeper, animals, chicken, bat, bee).
            HumanoidModel<?> bloodModel = null;
            boolean villagerBlood = false;
            boolean illagerBlood = false;

            switch (modelType) {
                case CREEPER:
                    renderCreeper(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case QUADRUPED: {
                    String mt = ragdoll.getMobType();
                    if (mt.contains("cat") || mt.contains("ocelot")) {
                        renderCat(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                        // wasSheared() carries "tamed" for cats (see PhysicsHooks); tamed cats
                        // wear a dyed collar. Ocelots are never tamed so this stays off for them.
                        if (mt.contains("cat") && ragdoll.wasSheared() && catCollarRoot != null) {
                            renderCatCollar(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg);
                        }
                    } else {
                        renderQuadruped(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    }
                    break;
                }
                case CHICKEN:
                    renderChicken(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case BAT:
                    renderBat(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case BEE:
                    renderBee(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case WITCH:
                    renderWitch(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case EQUINE:
                    renderEquine(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case WOLF:
                    renderWolf(ragdoll, poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case FOX:
                    renderFox(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case PANDA:
                    renderPanda(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case IRON_GOLEM:
                    renderIronGolem(poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case GOAT:
                    renderGoat(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case POLAR_BEAR:
                    renderPolarBear(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case TURTLE:
                    renderTurtle(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case ENDERMAN:
                    renderEnderman(poseStack, vc, buffer, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case CAMEL:
                    renderCamel(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case LLAMA:
                    renderLlama(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    if (isExactMobPath(ragdoll.getMobType(), "trader_llama")) {
                        renderLlamaRoot(ragdoll,llamaDecorRoot,poseStack,
                                buffer.getBuffer(RenderType.entityCutoutNoCull(TRADER_LLAMA_DECOR_TEXTURE)),
                                light,torso,head,larm,rarm,lleg,rleg);
                    }
                    break;
                case RABBIT:
                    renderRabbit(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case FROG:
                    renderFrog(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);
                    break;
                case HOGLIN:
                    renderHoglin(ragdoll,poseStack,vc,light,torso,head,larm,rarm,lleg,rleg);
                    break;
                case SNIFFER:
                    renderSniffer(ragdoll,poseStack,vc,light,torso,head,larm,rarm,lleg,rleg);
                    break;
                case RAVAGER:
                    renderRavager(poseStack,vc,light,torso,head,larm,rarm,lleg,rleg);
                    break;
                case PHANTOM:
                    renderPhantom(ragdoll,poseStack,vc,light,torso,head,larm,rarm,lleg,rleg);
                    renderPhantom(ragdoll,poseStack,buffer.getBuffer(RenderType.eyes(PHANTOM_EYES_TEXTURE)),
                            15728880,torso,head,larm,rarm,lleg,rleg);
                    break;
                case PARROT:
                    renderParrot(poseStack,vc,light,torso,head,larm,rarm,lleg,rleg);
                    break;
                case SLIME:
                    renderSlime(ragdoll,poseStack,buffer,light,torso);
                    break;
                case MAGMA_CUBE:
                    renderMagmaCube(poseStack,vc,light,torso);
                    break;
                case SILVERFISH: renderSilverfish(poseStack,vc,light,torso,head,larm,rarm,lleg,rleg); break;
                case ENDERMITE: renderEndermite(poseStack,vc,light,torso,head,larm,rarm,lleg,rleg); break;
                case ALLAY: renderAllay(poseStack,buffer.getBuffer(RenderType.entityTranslucent(texture)),light,torso,head,larm,rarm,lleg,rleg); break;
                case STRIDER:
                    renderStrider(ragdoll,striderRoot,poseStack,vc,light,torso,lleg,rleg);
                    if(ragdoll.isSaddledPig()) renderStrider(ragdoll,striderSaddleRoot,poseStack,buffer.getBuffer(RenderType.entityCutoutNoCull(STRIDER_SADDLE_TEXTURE)),light,torso,lleg,rleg);
                    break;
                case SNOW_GOLEM: renderSnowGolem(ragdoll,poseStack,buffer,vc,light,torso,head,larm,rarm,lleg); break;
                case BLAZE: renderBlaze(poseStack,vc,15728880,torso,head,larm,rarm,lleg,rleg); break;
                case SPIDER:
                    renderSpider(ragdoll,poseStack,vc,light,torso,head,larm,rarm,lleg,rleg);
                    renderSpider(ragdoll,poseStack,buffer.getBuffer(RenderType.eyes(SPIDER_EYES_TEXTURE)),15728880,torso,head,larm,rarm,lleg,rleg);
                    break;
                case SHULKER: renderShulker(poseStack,vc,light,torso,head); break;
                case GHAST: renderGhast(ragdoll,poseStack,vc,light,torso); break;
                case VEX: renderVex(poseStack,buffer.getBuffer(RenderType.entityTranslucent(texture)),light,torso,head,larm,rarm); break;
                case WARDEN:
                    renderWarden(poseStack,vc,light,torso,head,larm,rarm,lleg,rleg);
                    renderWarden(poseStack,buffer.getBuffer(RenderType.entityTranslucentEmissive(WARDEN_BIOLUMINESCENT_TEXTURE)),15728880,torso,head,larm,rarm,lleg,rleg);
                    break;
                case ILLAGER: {
                    // ILLAGER covers three texture/UV families: zombie villagers (vanilla
                    // ZombieVillagerModel — a humanoid), plain villagers + wandering traders
                    // (VillagerModel), and true illagers (pillager/vindicator/…). Route each
                    // to the model its texture is actually drawn for.
                    // Path only, never the whole id: a namespace containing "villager" must not
                    // route an otherwise plain humanoid onto VillagerModel geometry.
                    String mt = MobModelHelper.mobPath(ragdoll.getMobType());
                    if (mt.contains("zombie_villager")) {
                        renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, zombieVillagerModel, humanoidScale);
                        bloodModel = zombieVillagerModel;
                    } else if (!mt.contains("zombie") && (mt.contains("villager") || mt.contains("wandering_trader"))) {
                        renderVillager(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
                        villagerBlood = true;
                    } else {
                        renderIllager(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
                        illagerBlood = true;
                    }
                    break;
                }
                case HUMANOID_SKELETON:
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, skeletonModel, humanoidScale);
                    bloodModel = skeletonModel;
                    break;
                case HUMANOID_DROWNED:
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, drownedModel, humanoidScale);
                    bloodModel = drownedModel;
                    break;
                default:
                    HumanoidModel<?> humanoidModel = humanoidModelFor(ragdoll);
                    renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidModel, humanoidScale);
                    renderModdedHumanoidOverlays(humanoidModel, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
                    bloodModel = humanoidModel;
                    break;
            }

            // Procedural blood carried over from the live mob, drawn on the same physics-posed
            // parts (under armor/overlays). No-op unless Better Blood Overlay is installed and
            // the mob was bleeding at death.
            Object bloodId = ragdoll.getOriginalEntityId();
            if (bloodModel != null) {
                renderHumanoidBlood(bloodId, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, bloodModel, humanoidScale);
            } else if (villagerBlood) {
                renderVillagerBlood(bloodId, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
            } else if (illagerBlood) {
                renderIllagerBlood(bloodId, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
            }

            for (MobOverlay overlay : overlaysFor(ragdoll)) {
                renderOverlay(overlay, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
            }

            if (isHumanoidType(modelType)) {
                double armorDistSq = RagdollifiedConfig.getArmorRenderDistanceSq();
                double geckoDistSq = RagdollifiedConfig.getGeckoLibArmorRenderDistanceSq();

                if (distSq <= armorDistSq) {
                    renderMobVanillaArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);

                    if (distSq <= geckoDistSq) {
                        renderMobGeckoLibArmor(ragdoll, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
                    }
                }
            }
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Error rendering mob ragdoll", e);
        } finally {
            poseStack.popPose();
        }
    }

    private static boolean isHumanoidType(MobModelHelper.ModelType type) {
        return type == MobModelHelper.ModelType.HUMANOID_STANDARD ||
                type == MobModelHelper.ModelType.HUMANOID_SKELETON ||
                type == MobModelHelper.ModelType.HUMANOID_DROWNED ||
                type == MobModelHelper.ModelType.ILLAGER;
    }

    // Which humanoid model a generic humanoid ragdoll is drawn on.
    //
    // An optional compatibility adapter may cache a mob's own model when its texture is authored
    // for non-vanilla geometry. Extra child parts follow the six physics-posed roots automatically.
    //
    // Vanilla mobs deliberately stay on Ragdollified's own isolated trees.
    private static HumanoidModel<?> humanoidModelFor(ClientRagdoll ragdoll) {
        HumanoidModel<?> modded = ClientMobModelCache.getModel(ragdoll.getMobType());
        if (modded != null) return modded;

        // A modded mob with no cached model is about to be drawn on vanilla geometry. That is
        // right for mods that use vanilla UVs and badly wrong for the ones that do not, and the
        // difference is invisible from the outside — so name it once.
        if (!ragdoll.getMobType().startsWith("minecraft:")) {
            warnOnce(NO_MODEL_WARNED, ragdoll.getMobType(),
                    "No captured model for {} - drawing its ragdoll on the vanilla humanoid model. "
                            + "If its texture is not authored for vanilla UVs this will look wrong.");
        }
        return MobModelHelper.mobPath(ragdoll.getMobType()).contains("piglin")
                ? piglinModel : standardHumanoidModel;
    }

    // Diagnostics for the two ways a modded ragdoll silently comes out wrong. Once per mob type:
    // these sit in the render loop, and a repeating line would be worse than no line at all.
    private static final Set<String> UNTEXTURED_WARNED = ConcurrentHashMap.newKeySet();
    private static final Set<String> NO_MODEL_WARNED = ConcurrentHashMap.newKeySet();

    private static void warnOnce(Set<String> seen, String mobType, String message) {
        if (seen.add(mobType)) Ragdollified.LOGGER.warn(message, mobType);
    }

    // An overlay part a model keeps as a root sibling of the limb it covers, rather than a child.
    private record OverlayPart(ModelPart part, RagdollPart anchor) {}

    // Which sibling-overlay names anchor to which physics part. PlayerModel's second skin layer
    // set, plus the wear/body names modded humanoids use for the same idea. Root-level overlays
    // are otherwise invisible on a body posed limb by limb.
    private static final Map<String, RagdollPart> OVERLAY_ANCHORS = Map.ofEntries(
            Map.entry("jacket", RagdollPart.TORSO),
            Map.entry("bodywear", RagdollPart.TORSO),
            Map.entry("breasts", RagdollPart.TORSO),
            Map.entry("breastswear", RagdollPart.TORSO),
            Map.entry("breastplate", RagdollPart.TORSO),
            Map.entry("leftsleeve", RagdollPart.LEFT_ARM),
            Map.entry("leftarmwear", RagdollPart.LEFT_ARM),
            Map.entry("rightsleeve", RagdollPart.RIGHT_ARM),
            Map.entry("rightarmwear", RagdollPart.RIGHT_ARM),
            Map.entry("leftpants", RagdollPart.LEFT_LEG),
            Map.entry("leftlegwear", RagdollPart.LEFT_LEG),
            Map.entry("rightpants", RagdollPart.RIGHT_LEG),
            Map.entry("rightlegwear", RagdollPart.RIGHT_LEG));

    // Resolved once per model instance — the reflection is not worth repeating every frame, and
    // these are renderer singletons.
    private static final Map<HumanoidModel<?>, List<OverlayPart>> OVERLAY_CACHE = new ConcurrentHashMap<>();

    // Draw the overlay parts renderHumanoidMob cannot reach. It poses six named parts plus hat,
    // and anything parented to those rides along for free — but a part hung off the model root
    // is drawn by nothing at all.
    private static void renderModdedHumanoidOverlays(HumanoidModel<?> model, PoseStack poseStack,
                                                     VertexConsumer vc, int light,
                                                     RagdollTransform torso, RagdollTransform head,
                                                     RagdollTransform larm, RagdollTransform rarm,
                                                     RagdollTransform lleg, RagdollTransform rleg,
                                                     HumanoidScale scale) {
        List<OverlayPart> overlays = OVERLAY_CACHE.computeIfAbsent(model, ClientRagdollRenderer::resolveOverlayParts);
        if (overlays.isEmpty()) return;

        for (OverlayPart overlay : overlays) {
            RagdollTransform transform = switch (overlay.anchor()) {
                case HEAD -> head;
                case LEFT_ARM -> larm;
                case RIGHT_ARM -> rarm;
                case LEFT_LEG -> lleg;
                case RIGHT_LEG -> rleg;
                default -> torso;
            };
            renderHumanoidPartPhysics(poseStack, vc, overlay.part(), transform, torso, light, overlay.anchor(), scale);
        }
    }

    private static List<OverlayPart> resolveOverlayParts(HumanoidModel<?> model) {
        // Everything already drawn: the six posed parts, hat, and all of their descendants.
        Set<ModelPart> drawn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ModelPart posed : new ModelPart[]{model.head, model.hat, model.body,
                model.leftArm, model.rightArm, model.leftLeg, model.rightLeg}) {
            posed.getAllParts().forEach(drawn::add);
        }

        List<OverlayPart> out = new java.util.ArrayList<>();
        for (Class<?> cls = model.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType() != ModelPart.class) continue;
                RagdollPart anchor = OVERLAY_ANCHORS.get(field.getName().toLowerCase().replace("_", ""));
                if (anchor == null) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(model) instanceof ModelPart part && drawn.add(part)) {
                        out.add(new OverlayPart(part, anchor));
                    }
                } catch (Exception ignored) {
                    // A model that will not hand over a field simply loses that overlay.
                }
            }
        }
        return List.copyOf(out);
    }

    private static void renderHumanoidMob(PoseStack poseStack, VertexConsumer vc, int light,
                                           RagdollTransform torso, RagdollTransform head,
                                           RagdollTransform larm, RagdollTransform rarm,
                                           RagdollTransform lleg, RagdollTransform rleg,
                                           HumanoidModel<?> model) {
        renderHumanoidMob(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, model, HumanoidScale.ADULT);
    }

    private static void renderHumanoidMob(PoseStack poseStack, VertexConsumer vc, int light,
                                           RagdollTransform torso, RagdollTransform head,
                                           RagdollTransform larm, RagdollTransform rarm,
                                           RagdollTransform lleg, RagdollTransform rleg,
                                           HumanoidModel<?> model, HumanoidScale modelScale) {
        renderHumanoidPartPhysics(poseStack, vc, model.body, torso, torso, light, RagdollPart.TORSO, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.head, head, torso, light, RagdollPart.HEAD, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.leftArm, larm, torso, light, RagdollPart.LEFT_ARM, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, model.rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM, modelScale);
        // Second/overlay skin layer. HumanoidModel's "hat" is a sibling of "head" (not a
        // child), so the head pass above never draws it — render it explicitly to match
        // vanilla, which draws the hat for every humanoid (transparent on most mob textures).
        renderHumanoidPartPhysics(poseStack, vc, model.hat, head, torso, light, RagdollPart.HEAD, modelScale);
    }

    // Better Blood Overlay pass for a humanoid ragdoll: draws the decals captured from the live
    // mob onto each physics-posed part the same way BBO's own renderWounds does — same model
    // part, translucent, scaled 1.001 to sit just above the skin. No-op without BBO or blood.
    private static void renderHumanoidBlood(Object id, PoseStack poseStack, MultiBufferSource buffer,
                                            int light, RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg,
                                            HumanoidModel<?> model, HumanoidScale scale) {
        if (!BetterBloodOverlayCompat.hasBlood(id)) return;

        renderBloodPart(id, "body",      model.body,     poseStack, buffer, torso, torso, light, RagdollPart.TORSO,     scale);
        renderBloodPart(id, "head",      model.head,     poseStack, buffer, head,  torso, light, RagdollPart.HEAD,      scale);
        renderBloodPart(id, "left_leg",  model.leftLeg,  poseStack, buffer, lleg,  torso, light, RagdollPart.LEFT_LEG,  scale);
        renderBloodPart(id, "right_leg", model.rightLeg, poseStack, buffer, rleg,  torso, light, RagdollPart.RIGHT_LEG, scale);
        renderBloodPart(id, "left_arm",  model.leftArm,  poseStack, buffer, larm,  torso, light, RagdollPart.LEFT_ARM,  scale);
        renderBloodPart(id, "right_arm", model.rightArm, poseStack, buffer, rarm,  torso, light, RagdollPart.RIGHT_ARM, scale);

        // Second skin layer. Only the player profile maps wounds to these overlay parts, and
        // BBO only draws them when overlay-blood is enabled — so this is a no-op for mobs
        // (decalsForPart returns empty) and gated to match BBO for players.
        if (model instanceof PlayerModel<?> pm && BetterBloodOverlayCompat.isOverlayBloodEnabled()) {
            renderBloodPart(id, "hat",          pm.hat,         poseStack, buffer, head,  torso, light, RagdollPart.HEAD,      scale);
            renderBloodPart(id, "jacket",       pm.jacket,      poseStack, buffer, torso, torso, light, RagdollPart.TORSO,     scale);
            renderBloodPart(id, "left_sleeve",  pm.leftSleeve,  poseStack, buffer, larm,  torso, light, RagdollPart.LEFT_ARM,  scale);
            renderBloodPart(id, "right_sleeve", pm.rightSleeve, poseStack, buffer, rarm,  torso, light, RagdollPart.RIGHT_ARM, scale);
            renderBloodPart(id, "left_pants",   pm.leftPants,   poseStack, buffer, lleg,  torso, light, RagdollPart.LEFT_LEG,  scale);
            renderBloodPart(id, "right_pants",  pm.rightPants,  poseStack, buffer, rleg,  torso, light, RagdollPart.RIGHT_LEG, scale);
        }
    }

    // Better Blood Overlay pass for illagers. They render on IllagerModel rather than
    // HumanoidModel but use the same per-part physics posing, so the parts are resolved by name
    // and renderBloodPart is reused. BBO's illager profile paints a combined "arms" site, which
    // is hidden here, so drawing left_arm and right_arm reproduces it correctly.
    private static void renderIllagerBlood(Object id, PoseStack poseStack, MultiBufferSource buffer, int light,
                                           RagdollTransform torso, RagdollTransform head,
                                           RagdollTransform larm, RagdollTransform rarm,
                                           RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        if (!BetterBloodOverlayCompat.hasBlood(id)) return;
        ModelPart root = illagerModel.root();
        renderBloodPart(id, "body",      root.getChild("body"),      poseStack, buffer, torso, torso, light, RagdollPart.TORSO,     scale);
        renderBloodPart(id, "head",      root.getChild("head"),      poseStack, buffer, head,  torso, light, RagdollPart.HEAD,      scale);
        renderBloodPart(id, "left_leg",  root.getChild("left_leg"),  poseStack, buffer, lleg,  torso, light, RagdollPart.LEFT_LEG,  scale);
        renderBloodPart(id, "right_leg", root.getChild("right_leg"), poseStack, buffer, rleg,  torso, light, RagdollPart.RIGHT_LEG, scale);
        renderBloodPart(id, "left_arm",  root.getChild("left_arm"),  poseStack, buffer, larm,  torso, light, RagdollPart.LEFT_ARM,  scale);
        renderBloodPart(id, "right_arm", root.getChild("right_arm"), poseStack, buffer, rarm,  torso, light, RagdollPart.RIGHT_ARM, scale);
    }

    // Better Blood Overlay pass for villagers and wandering traders. They render on the vanilla
    // VillagerModel through the animal-part path, and BBO's villager profile maps both arms to
    // one combined "arms" site, so this mirrors renderVillagerParts on the same posed parts.
    private static void renderVillagerBlood(Object id, PoseStack poseStack, MultiBufferSource buffer, int light,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        if (!BetterBloodOverlayCompat.hasBlood(id)) return;
        ModelPart headPart = villagerRoot.getChild("head");
        ModelPart body     = villagerRoot.getChild("body");
        ModelPart arms     = villagerRoot.getChild("arms");
        ModelPart leftLeg  = villagerRoot.getChild("left_leg");
        ModelPart rightLeg = villagerRoot.getChild("right_leg");

        renderBloodVillagerPart(id, "body",      body,     poseStack, buffer, torso, torso, light, scale.body());
        renderBloodVillagerPart(id, "head",      headPart, poseStack, buffer, head,  torso, light, scale.head());
        renderBloodVillagerPart(id, "left_leg",  leftLeg,  poseStack, buffer, lleg,  torso, light, scale.body());
        renderBloodVillagerPart(id, "right_leg", rightLeg, poseStack, buffer, rleg,  torso, light, scale.body());
        // Combined "arms" part, anchored to the torso with the same offsets/pitch as the base
        // pass (see renderVillagerParts). Both arm-limb wounds map here.
        var armDecals = BetterBloodOverlayCompat.decalsForPart(id, "arms");
        if (!armDecals.isEmpty()) {
            renderBloodAnimalPart(arms, poseStack, buffer, torso, torso, 0.0F, -3.0F, -1.0F, -0.75F, light, scale.body(), armDecals);
        }
    }

    // Blood on one villager part, centred on its physics body exactly like renderVillagerPart.
    private static void renderBloodVillagerPart(Object id, String partName, ModelPart part,
                                                PoseStack poseStack, MultiBufferSource buffer,
                                                RagdollTransform transform, RagdollTransform torso,
                                                int light, float modelScale) {
        var decals = BetterBloodOverlayCompat.decalsForPart(id, partName);
        if (decals.isEmpty()) return;
        org.joml.Vector3f off = setPosForPart(part, 0);
        renderBloodAnimalPart(part, poseStack, buffer, transform, torso, off.x, off.y, off.z, 0, light, modelScale, decals);
    }

    // Pose a part to its physics transform like renderAnimalPart, then draw blood decals over it
    // at 1.001 scale, translucent. The animal-path counterpart of renderBloodPart.
    private static void renderBloodAnimalPart(ModelPart part, PoseStack poseStack, MultiBufferSource buffer,
                                              RagdollTransform transform, RagdollTransform torso,
                                              float setPosX, float setPosY, float setPosZ,
                                              float defaultXRot, int light, float modelScale,
                                              java.util.List<BetterBloodOverlayCompat.Decal> decals) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            if (modelScale != 1.0f) {
                poseStack.scale(modelScale, modelScale, modelScale);
            }

            part.setPos(setPosX, setPosY, setPosZ);
            part.xRot = defaultXRot;
            part.yRot = 0;
            part.zRot = 0;

            poseStack.pushPose();
            poseStack.scale(1.001f, 1.001f, 1.001f);
            for (BetterBloodOverlayCompat.Decal d : decals) {
                VertexConsumer bvc = buffer.getBuffer(RenderType.entityTranslucent(d.texture()));
                part.render(poseStack, bvc, light, OverlayTexture.NO_OVERLAY, d.r(), d.g(), d.b(), d.alpha());
            }
            poseStack.popPose();
        } finally {
            poseStack.popPose();
        }
    }

    // Pose a part to its physics transform, identically to renderHumanoidPartPhysics, and draw
    // whatever blood decals are mapped to partName.
    private static void renderBloodPart(Object ragdollId, String partName, ModelPart part,
                                        PoseStack poseStack, MultiBufferSource buffer,
                                        RagdollTransform transform, RagdollTransform torso,
                                        int light, RagdollPart ragdollPart, HumanoidScale scale) {
        if (transform == null) return;
        var decals = BetterBloodOverlayCompat.decalsForPart(ragdollId, partName);
        if (decals.isEmpty()) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

            switch (ragdollPart) {
                case HEAD:      part.setPos(0, 4, 0);    break;
                case TORSO:     part.setPos(0, -6, 0);   break;
                case LEFT_ARM:  part.setPos(-1, -4, 0);  break;
                case RIGHT_ARM: part.setPos(1, -4, 0);   break;
                case LEFT_LEG:
                case RIGHT_LEG: part.setPos(0, -6, 0);   break;
            }
            part.xRot = 0;
            part.yRot = 0;
            part.zRot = 0;

            // Nudge just above the skin so blood never z-fights with the base texture — same
            // 1.001 scale BBO uses in renderWounds.
            poseStack.pushPose();
            poseStack.scale(1.001f, 1.001f, 1.001f);
            for (BetterBloodOverlayCompat.Decal d : decals) {
                VertexConsumer bvc = buffer.getBuffer(RenderType.entityTranslucent(d.texture()));
                part.render(poseStack, bvc, light, OverlayTexture.NO_OVERLAY, d.r(), d.g(), d.b(), d.alpha());
            }
            poseStack.popPose();
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderIllager(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg, HumanoidScale modelScale) {
        ModelPart root = illagerModel.root();
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        ModelPart leftArm = root.getChild("left_arm");
        ModelPart rightArm = root.getChild("right_arm");

        try {
            root.getChild("arms").visible = false;
        } catch (Exception ignored) {}
        leftArm.visible = true;
        rightArm.visible = true;

        renderHumanoidPartPhysics(poseStack, vc, body, torso, torso, light, RagdollPart.TORSO, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head, torso, light, RagdollPart.HEAD, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, leftLeg, lleg, torso, light, RagdollPart.LEFT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, rightLeg, rleg, torso, light, RagdollPart.RIGHT_LEG, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, leftArm, larm, torso, light, RagdollPart.LEFT_ARM, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, rightArm, rarm, torso, light, RagdollPart.RIGHT_ARM, modelScale);
    }

    // Regular villagers and wandering traders, on the vanilla VillagerModel whose UVs the
    // villager textures are drawn for, with separate short arms replacing its single combined
    // "arms" part so every part textures correctly and the overlays can cover the whole body.
    private static void renderVillager(PoseStack poseStack, VertexConsumer vc, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        renderVillagerParts(poseStack, vc, light, torso, head, larm, rarm, lleg, rleg, scale);
    }

    // Draws the villager body with whatever texture vc targets, reused for the base skin and
    // for each profession, type, and level overlay layer.
    private static void renderVillagerParts(PoseStack poseStack, VertexConsumer vc, int light,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg, HumanoidScale scale) {
        renderVillagerLikeParts(villagerRoot, poseStack, vc, light,
                torso, head, lleg, rleg, scale.body(), scale.head());
    }

    // WitchModel extends VillagerModel and deliberately retains the same body, crossed arms,
    // and two legs. Its replacement head owns the nose/mole and the complete four-piece hat,
    // so rendering that head subtree preserves every witch-specific cube.
    private static void renderWitch(PoseStack poseStack, VertexConsumer vc, int light,
                                    RagdollTransform torso, RagdollTransform head,
                                    RagdollTransform larm, RagdollTransform rarm,
                                    RagdollTransform lleg, RagdollTransform rleg) {
        renderVillagerLikeParts(witchRoot, poseStack, vc, light,
                torso, head, lleg, rleg, 0.9375f, 0.9375f);
    }

    private static void renderVillagerLikeParts(ModelPart root, PoseStack poseStack, VertexConsumer vc, int light,
                                                 RagdollTransform torso, RagdollTransform head,
                                                 RagdollTransform lleg, RagdollTransform rleg,
                                                 float bodyScale, float headScale) {
        ModelPart headPart = root.getChild("head");
        ModelPart body     = root.getChild("body");
        ModelPart arms     = root.getChild("arms");
        ModelPart leftLeg  = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");

        renderVillagerPart(poseStack, vc, body,     torso, torso, light, bodyScale);
        renderVillagerPart(poseStack, vc, headPart, head,  torso, light, headScale);
        renderVillagerPart(poseStack, vc, leftLeg,  lleg,  torso, light, bodyScale);
        renderVillagerPart(poseStack, vc, rightLeg, rleg,  torso, light, bodyScale);
        // Villager arms are the vanilla crossed-arms unit (two stubs + the bar connecting
        // them). A ragdoll's two separate arm bodies can't carry a rigid connector, so — per
        // the chosen design — we anchor the whole arms part to the torso at its natural
        // body-relative pose and let it tumble with the body, keeping the folded-arms look.
        // Placement: arms pivot (0,3,-1) minus the body-cube centre (0,6,0) → (0,-3,-1), with
        // the model's -0.75 rad forward pitch preserved via defaultXRot.
        renderAnimalPart(poseStack, vc, arms, torso, torso, 0.0F, -3.0F, -1.0F, -0.75F, light, bodyScale);
    }

    // One villager part, centred on its physics body via setPosForPart, which handles the tall
    // head without hand-tuned offsets just as the animal path does.
    private static void renderVillagerPart(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                           RagdollTransform transform, RagdollTransform torso,
                                           int light, float modelScale) {
        org.joml.Vector3f off = setPosForPart(part, 0);
        renderAnimalPart(poseStack, vc, part, transform, torso, off.x, off.y, off.z, 0, light, modelScale);
    }

    private static void renderCreeper(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc,
                                       MultiBufferSource buffer, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = creeperModel.root();
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");

        renderHumanoidPartPhysics(poseStack, vc, body, torso, torso, light, RagdollPart.TORSO);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head, torso, light, RagdollPart.HEAD);

        renderAnimalPart(poseStack, vc, leftHind, lleg, torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vc, rightHind, rleg, torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vc, leftFront, larm, torso, 0, -3, 0, 0, light);
        renderAnimalPart(poseStack, vc, rightFront, rarm, torso, 0, -3, 0, 0, light);
        // Charged-creeper energy swirl handled by the OverlayRegistry dispatch in
        // renderMobRagdoll — no inline rendering here.
    }

    private static void renderQuadruped(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc,
                                         MultiBufferSource buffer, int light,
                                         RagdollTransform torso, RagdollTransform head,
                                         RagdollTransform larm, RagdollTransform rarm,
                                         RagdollTransform lleg, RagdollTransform rleg) {
        String mobType = ragdoll.getMobType();
        boolean isSheep = mobType.contains("sheep");
        ModelPart root;
        if (mobType.contains("cow") || mobType.contains("mooshroom")) root = cowRoot;
        else if (isSheep) root = sheepRoot;
        else if (mobType.contains("pig")) root = pigRoot;
        else root = cowRoot;

        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");

        float halfPI = (float) (Math.PI / 2);
        // setPos values are derived from each ModelPart's cube bbox center (post-bake-time
        // pi/2 X rotation for the body) so the cube's geometric center coincides with
        // its physics body's transform origin. Replaces the previous per-mob hardcoded
        // constants which were eyeballed and noticeably off for sheep + chicken wings.
        org.joml.Vector3f bodyOff       = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff       = setPosForPart(headPart, 0);
        org.joml.Vector3f leftHindOff   = setPosForPart(leftHind, 0);
        org.joml.Vector3f rightHindOff  = setPosForPart(rightHind, 0);
        org.joml.Vector3f leftFrontOff  = setPosForPart(leftFront, 0);
        org.joml.Vector3f rightFrontOff = setPosForPart(rightFront, 0);

        float bodyScale = ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f;
        renderAnimalPart(poseStack, vc, body,       torso, torso, bodyOff.x,       bodyOff.y,       bodyOff.z,       halfPI, light, bodyScale);
        renderAnimalPart(poseStack, vc, headPart,   head,  torso, headOff.x,       headOff.y,       headOff.z,       0,      light);
        renderAnimalPart(poseStack, vc, leftHind,   lleg,  torso, leftHindOff.x,   leftHindOff.y,   leftHindOff.z,   0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightHind,  rleg,  torso, rightHindOff.x,  rightHindOff.y,  rightHindOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, leftFront,  larm,  torso, leftFrontOff.x,  leftFrontOff.y,  leftFrontOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightFront, rarm,  torso, rightFrontOff.x, rightFrontOff.y, rightFrontOff.z, 0,      light, bodyScale);
        // Pig saddle + sheep wool overlays are handled by the OverlayRegistry dispatch
        // in renderMobRagdoll — no inline rendering here.
    }

    private static void renderEquine(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc,
                                     MultiBufferSource buffer, int light,
                                     RagdollTransform torso, RagdollTransform head,
                                     RagdollTransform larm, RagdollTransform rarm,
                                     RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = equineRoot(ragdoll.getMobType());
        renderEquineParts(ragdoll, root, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg);

        int markings = ragdoll.getHorseMarkingsId();
        if (isExactMobPath(ragdoll.getMobType(), "horse") && markings > 0 && markings < HORSE_MARKING_TEXTURES.length) {
            VertexConsumer markingsVc = buffer.getBuffer(RenderType.entityTranslucent(HORSE_MARKING_TEXTURES[markings]));
            renderEquineParts(ragdoll, root, poseStack, markingsVc, light, torso, head, larm, rarm, lleg, rleg);
        }
    }

    // WolfModel uses an empty head pivot with a real_head child, plus separate shoulder and
    // tail roots. Render the cube-bearing head directly so its union of head/ears/muzzle is
    // centred on the head rigid body; shoulder and tail remain attached to the torso.
    private static void renderWolf(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc,
                                   MultiBufferSource buffer, int light,
                                   RagdollTransform torso, RagdollTransform head,
                                   RagdollTransform larm, RagdollTransform rarm,
                                   RagdollTransform lleg, RagdollTransform rleg) {
        renderWolfParts(ragdoll, poseStack, vc, light, torso, head, larm, rarm, lleg, rleg,
                false, 1f, 1f, 1f);
        if (ragdoll.wasSheared()) { // packed tamed flag; see PhysicsHooks
            float[] rgb = net.minecraft.world.item.DyeColor.byId(ragdoll.getDyeColorId() & 0xF)
                    .getTextureDiffuseColors();
            VertexConsumer collar = buffer.getBuffer(RenderType.entityCutoutNoCull(WOLF_COLLAR_TEXTURE));
            renderWolfParts(ragdoll, poseStack, collar, light, torso, head, larm, rarm, lleg, rleg,
                    true, rgb[0], rgb[1], rgb[2]);
        }
    }

    private static void renderWolfParts(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                        RagdollTransform torso, RagdollTransform head,
                                        RagdollTransform larm, RagdollTransform rarm,
                                        RagdollTransform lleg, RagdollTransform rleg,
                                        boolean tinted, float red, float green, float blue) {
        ModelPart body = wolfRoot.getChild("body");
        ModelPart upperBody = wolfRoot.getChild("upper_body");
        ModelPart realHead = wolfRoot.getChild("head").getChild("real_head");
        ModelPart tail = wolfRoot.getChild("tail");
        ModelPart rightHind = wolfRoot.getChild("right_hind_leg");
        ModelPart leftHind = wolfRoot.getChild("left_hind_leg");
        ModelPart rightFront = wolfRoot.getChild("right_front_leg");
        ModelPart leftFront = wolfRoot.getChild("left_front_leg");
        float halfPI = (float) (Math.PI / 2.0);
        float bodyScale = ragdoll.isBabyWolf() ? 0.5f : 1f;
        float headScale = 1f; // WolfModel's default AgeableListModel keeps a pup's head full-size.
        org.joml.Vector3f bodyOff = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff = setPosForPart(realHead, 0);
        org.joml.Vector3f lhOff = setPosForPart(leftHind, 0);
        org.joml.Vector3f rhOff = setPosForPart(rightHind, 0);
        org.joml.Vector3f lfOff = setPosForPart(leftFront, 0);
        org.joml.Vector3f rfOff = setPosForPart(rightFront, 0);
        if (!tinted) {
            renderAnimalPart(poseStack, vc, body, torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, halfPI, light, bodyScale);
            renderAnimalPart(poseStack, vc, upperBody, torso, torso, -1f, 0f, -7.5f, halfPI, light, bodyScale);
            renderAnimalPart(poseStack, vc, tail, torso, torso, -1f, -2f, 3.5f, (float)Math.PI / 5f, light, bodyScale);
            renderAnimalPart(poseStack, vc, realHead, head, torso, headOff.x, headOff.y, headOff.z, 0, light, headScale);
            renderAnimalPart(poseStack, vc, leftHind, lleg, torso, lhOff.x, lhOff.y, lhOff.z, 0, light, bodyScale);
            renderAnimalPart(poseStack, vc, rightHind, rleg, torso, rhOff.x, rhOff.y, rhOff.z, 0, light, bodyScale);
            renderAnimalPart(poseStack, vc, leftFront, larm, torso, lfOff.x, lfOff.y, lfOff.z, 0, light, bodyScale);
            renderAnimalPart(poseStack, vc, rightFront, rarm, torso, rfOff.x, rfOff.y, rfOff.z, 0, light, bodyScale);
        } else {
            renderAnimalPartTinted(poseStack, vc, body, torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, halfPI, light, red, green, blue, bodyScale);
            renderAnimalPartTinted(poseStack, vc, upperBody, torso, torso, -1f, 0f, -7.5f, halfPI, light, red, green, blue, bodyScale);
            renderAnimalPartTinted(poseStack, vc, tail, torso, torso, -1f, -2f, 3.5f, (float)Math.PI / 5f, light, red, green, blue, bodyScale);
            renderAnimalPartTinted(poseStack, vc, realHead, head, torso, headOff.x, headOff.y, headOff.z, 0, light, red, green, blue, headScale);
            renderAnimalPartTinted(poseStack, vc, leftHind, lleg, torso, lhOff.x, lhOff.y, lhOff.z, 0, light, red, green, blue, bodyScale);
            renderAnimalPartTinted(poseStack, vc, rightHind, rleg, torso, rhOff.x, rhOff.y, rhOff.z, 0, light, red, green, blue, bodyScale);
            renderAnimalPartTinted(poseStack, vc, leftFront, larm, torso, lfOff.x, lfOff.y, lfOff.z, 0, light, red, green, blue, bodyScale);
            renderAnimalPartTinted(poseStack, vc, rightFront, rarm, torso, rfOff.x, rfOff.y, rfOff.z, 0, light, red, green, blue, bodyScale);
        }
    }

    private static void renderFox(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                  RagdollTransform torso, RagdollTransform head,
                                  RagdollTransform larm, RagdollTransform rarm,
                                  RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body = foxRoot.getChild("body"); // includes the child tail
        ModelPart headPart = foxRoot.getChild("head");
        ModelPart rightHind = foxRoot.getChild("right_hind_leg");
        ModelPart leftHind = foxRoot.getChild("left_hind_leg");
        ModelPart rightFront = foxRoot.getChild("right_front_leg");
        ModelPart leftFront = foxRoot.getChild("left_front_leg");
        float halfPI = (float) (Math.PI / 2.0);
        float bodyScale = ragdoll.isBabyFox() ? 0.5f : 1f;
        float headScale = ragdoll.isBabyFox() ? 0.75f : 1f;
        org.joml.Vector3f bodyOff = setPosForPart(body, halfPI);
        org.joml.Vector3f lhOff = setPosForPart(leftHind, 0);
        org.joml.Vector3f rhOff = setPosForPart(rightHind, 0);
        org.joml.Vector3f lfOff = setPosForPart(leftFront, 0);
        org.joml.Vector3f rfOff = setPosForPart(rightFront, 0);
        renderAnimalPart(poseStack, vc, body, torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, halfPI, light, bodyScale);
        // Union centre of the head cube, ears, and nose is (1, 0, -3.5) in head-local pixels.
        renderAnimalPart(poseStack, vc, headPart, head, torso, -1f, 0f, 3.5f, 0, light, headScale);
        renderAnimalPart(poseStack, vc, leftHind, lleg, torso, lhOff.x, lhOff.y, lhOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightHind, rleg, torso, rhOff.x, rhOff.y, rhOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, leftFront, larm, torso, lfOff.x, lfOff.y, lfOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightFront, rarm, torso, rfOff.x, rfOff.y, rfOff.z, 0, light, bodyScale);
    }

    private static void renderPanda(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                    RagdollTransform torso, RagdollTransform head,
                                    RagdollTransform larm, RagdollTransform rarm,
                                    RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body = pandaRoot.getChild("body");
        ModelPart headPart = pandaRoot.getChild("head");
        ModelPart rightHind = pandaRoot.getChild("right_hind_leg");
        ModelPart leftHind = pandaRoot.getChild("left_hind_leg");
        ModelPart rightFront = pandaRoot.getChild("right_front_leg");
        ModelPart leftFront = pandaRoot.getChild("left_front_leg");
        float halfPI = (float)(Math.PI / 2.0);
        float bodyScale = ragdoll.isBabyPanda() ? 1f / 3f : 1f;
        float headScale = ragdoll.isBabyPanda() ? 1.5f / 2.7f : 1f;
        org.joml.Vector3f bodyOff = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff = setPosForPart(headPart, 0);
        org.joml.Vector3f lhOff = setPosForPart(leftHind, 0);
        org.joml.Vector3f rhOff = setPosForPart(rightHind, 0);
        org.joml.Vector3f lfOff = setPosForPart(leftFront, 0);
        org.joml.Vector3f rfOff = setPosForPart(rightFront, 0);
        renderAnimalPart(poseStack, vc, body, torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, halfPI, light, bodyScale);
        renderAnimalPart(poseStack, vc, headPart, head, torso, headOff.x, headOff.y, headOff.z, 0, light, headScale);
        renderAnimalPart(poseStack, vc, leftHind, lleg, torso, lhOff.x, lhOff.y, lhOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightHind, rleg, torso, rhOff.x, rhOff.y, rhOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, leftFront, larm, torso, lfOff.x, lfOff.y, lfOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightFront, rarm, torso, rfOff.x, rfOff.y, rfOff.z, 0, light, bodyScale);
    }

    private static void renderIronGolem(PoseStack poseStack, VertexConsumer base,
                                        MultiBufferSource buffer, int light,
                                        RagdollTransform torso, RagdollTransform head,
                                        RagdollTransform larm, RagdollTransform rarm,
                                        RagdollTransform lleg, RagdollTransform rleg) {
        renderIronGolemParts(poseStack, base, light, torso, head, larm, rarm, lleg, rleg);
        // A golem at death has zero health, which maps to vanilla's HIGH crackiness layer.
        VertexConsumer cracks = buffer.getBuffer(RenderType.entityCutoutNoCull(IRON_GOLEM_HIGH_CRACKS_TEXTURE));
        renderIronGolemParts(poseStack, cracks, light, torso, head, larm, rarm, lleg, rleg);
    }

    private static void renderIronGolemParts(PoseStack poseStack, VertexConsumer vc, int light,
                                             RagdollTransform torso, RagdollTransform head,
                                             RagdollTransform larm, RagdollTransform rarm,
                                             RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body = ironGolemRoot.getChild("body");
        ModelPart headPart = ironGolemRoot.getChild("head");
        ModelPart leftArm = ironGolemRoot.getChild("left_arm");
        ModelPart rightArm = ironGolemRoot.getChild("right_arm");
        ModelPart leftLeg = ironGolemRoot.getChild("left_leg");
        ModelPart rightLeg = ironGolemRoot.getChild("right_leg");
        org.joml.Vector3f bodyOff = setPosForPart(body, 0);
        org.joml.Vector3f headOff = setPosForPart(headPart, 0);
        org.joml.Vector3f laOff = setPosForPart(leftArm, 0);
        org.joml.Vector3f raOff = setPosForPart(rightArm, 0);
        org.joml.Vector3f llOff = setPosForPart(leftLeg, 0);
        org.joml.Vector3f rlOff = setPosForPart(rightLeg, 0);
        renderAnimalPart(poseStack, vc, body, torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, 0, light);
        renderAnimalPart(poseStack, vc, headPart, head, torso, headOff.x, headOff.y, headOff.z, 0, light);
        renderAnimalPart(poseStack, vc, leftArm, larm, torso, laOff.x, laOff.y, laOff.z, 0, light);
        renderAnimalPart(poseStack, vc, rightArm, rarm, torso, raOff.x, raOff.y, raOff.z, 0, light);
        renderAnimalPart(poseStack, vc, leftLeg, lleg, torso, llOff.x, llOff.y, llOff.z, 0, light);
        renderAnimalPart(poseStack, vc, rightLeg, rleg, torso, rlOff.x, rlOff.y, rlOff.z, 0, light);
    }

    private static void renderGoat(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                   RagdollTransform torso, RagdollTransform head,
                                   RagdollTransform larm, RagdollTransform rarm,
                                   RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body=goatRoot.getChild("body"), headPart=goatRoot.getChild("head");
        ModelPart lh=goatRoot.getChild("left_hind_leg"), rh=goatRoot.getChild("right_hind_leg");
        ModelPart lf=goatRoot.getChild("left_front_leg"), rf=goatRoot.getChild("right_front_leg");
        int horns = ragdoll.getDyeColorId();
        headPart.getChild("left_horn").visible = (horns & 1) != 0;
        headPart.getChild("right_horn").visible = (horns & 2) != 0;
        float bs=ragdoll.isBabyGoat()?.5f:1f, hs=ragdoll.isBabyGoat()?.6f:1f;
        org.joml.Vector3f bo=setPosForPart(body,0), ho=setPosForPart(headPart,0);
        org.joml.Vector3f lho=setPosForPart(lh,0), rho=setPosForPart(rh,0), lfo=setPosForPart(lf,0), rfo=setPosForPart(rf,0);
        renderAnimalPart(poseStack,vc,body,torso,torso,bo.x,bo.y,bo.z,0,light,bs);
        renderAnimalPart(poseStack,vc,headPart,head,torso,ho.x,ho.y,ho.z,0,light,hs);
        renderAnimalPart(poseStack,vc,lh,lleg,torso,lho.x,lho.y,lho.z,0,light,bs);
        renderAnimalPart(poseStack,vc,rh,rleg,torso,rho.x,rho.y,rho.z,0,light,bs);
        renderAnimalPart(poseStack,vc,lf,larm,torso,lfo.x,lfo.y,lfo.z,0,light,bs);
        renderAnimalPart(poseStack,vc,rf,rarm,torso,rfo.x,rfo.y,rfo.z,0,light,bs);
    }

    private static void renderPolarBear(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                        RagdollTransform torso, RagdollTransform head,
                                        RagdollTransform larm, RagdollTransform rarm,
                                        RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body=polarBearRoot.getChild("body"), headPart=polarBearRoot.getChild("head");
        ModelPart lh=polarBearRoot.getChild("left_hind_leg"), rh=polarBearRoot.getChild("right_hind_leg");
        ModelPart lf=polarBearRoot.getChild("left_front_leg"), rf=polarBearRoot.getChild("right_front_leg");
        float halfPI=(float)Math.PI/2f;
        float bs=(ragdoll.isBabyPolarBear()?.5f:1f)*1.2f;
        float hs=(ragdoll.isBabyPolarBear()?2f/3f:1f)*1.2f;
        org.joml.Vector3f bo=setPosForPart(body,halfPI), ho=setPosForPart(headPart,0);
        org.joml.Vector3f lho=setPosForPart(lh,0), rho=setPosForPart(rh,0), lfo=setPosForPart(lf,0), rfo=setPosForPart(rf,0);
        renderAnimalPart(poseStack,vc,body,torso,torso,bo.x,bo.y,bo.z,halfPI,light,bs);
        renderAnimalPart(poseStack,vc,headPart,head,torso,ho.x,ho.y,ho.z,0,light,hs);
        renderAnimalPart(poseStack,vc,lh,lleg,torso,lho.x,lho.y,lho.z,0,light,bs);
        renderAnimalPart(poseStack,vc,rh,rleg,torso,rho.x,rho.y,rho.z,0,light,bs);
        renderAnimalPart(poseStack,vc,lf,larm,torso,lfo.x,lfo.y,lfo.z,0,light,bs);
        renderAnimalPart(poseStack,vc,rf,rarm,torso,rfo.x,rfo.y,rfo.z,0,light,bs);
    }

    private static void renderTurtle(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                     RagdollTransform torso, RagdollTransform head,
                                     RagdollTransform larm, RagdollTransform rarm,
                                     RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body=turtleRoot.getChild("body"), headPart=turtleRoot.getChild("head");
        ModelPart egg=turtleRoot.getChild("egg_belly");
        ModelPart lh=turtleRoot.getChild("left_hind_leg"), rh=turtleRoot.getChild("right_hind_leg");
        ModelPart lf=turtleRoot.getChild("left_front_leg"), rf=turtleRoot.getChild("right_front_leg");
        float halfPI=(float)Math.PI/2f, s=ragdoll.isBabyTurtle()?1f/6f:1f;
        org.joml.Vector3f bo=setPosForPart(body,halfPI), ho=setPosForPart(headPart,0);
        org.joml.Vector3f lho=setPosForPart(lh,0), rho=setPosForPart(rh,0), lfo=setPosForPart(lf,0), rfo=setPosForPart(rf,0);
        renderAnimalPart(poseStack,vc,body,torso,torso,bo.x,bo.y,bo.z,halfPI,light,s);
        if (!ragdoll.isBabyTurtle() && ragdoll.wasSheared()) {
            renderAnimalPart(poseStack,vc,egg,torso,torso,0,-8.5f,-13f,halfPI,light);
        }
        renderAnimalPart(poseStack,vc,headPart,head,torso,ho.x,ho.y,ho.z,0,light,s);
        renderAnimalPart(poseStack,vc,lh,lleg,torso,lho.x,lho.y,lho.z,0,light,s);
        renderAnimalPart(poseStack,vc,rh,rleg,torso,rho.x,rho.y,rho.z,0,light,s);
        renderAnimalPart(poseStack,vc,lf,larm,torso,lfo.x,lfo.y,lfo.z,0,light,s);
        renderAnimalPart(poseStack,vc,rf,rarm,torso,rfo.x,rfo.y,rfo.z,0,light,s);
    }

    private static void renderEnderman(PoseStack poseStack, VertexConsumer base, MultiBufferSource buffer, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg) {
        renderEndermanParts(poseStack,base,light,torso,head,larm,rarm,lleg,rleg);
        renderEndermanParts(poseStack,buffer.getBuffer(RenderType.eyes(ENDERMAN_EYES_TEXTURE)),
                light,torso,head,larm,rarm,lleg,rleg);
    }

    private static void renderEndermanParts(PoseStack poseStack, VertexConsumer vc, int light,
                                            RagdollTransform torso, RagdollTransform head,
                                            RagdollTransform larm, RagdollTransform rarm,
                                            RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body=endermanRoot.getChild("body"), headPart=endermanRoot.getChild("head"), hat=endermanRoot.getChild("hat");
        ModelPart la=endermanRoot.getChild("left_arm"), ra=endermanRoot.getChild("right_arm");
        ModelPart ll=endermanRoot.getChild("left_leg"), rl=endermanRoot.getChild("right_leg");
        org.joml.Vector3f bo=setPosForPart(body,0), ho=setPosForPart(headPart,0), hato=setPosForPart(hat,0);
        org.joml.Vector3f lao=setPosForPart(la,0), rao=setPosForPart(ra,0), llo=setPosForPart(ll,0), rlo=setPosForPart(rl,0);
        renderAnimalPart(poseStack,vc,body,torso,torso,bo.x,bo.y,bo.z,0,light);
        renderAnimalPart(poseStack,vc,headPart,head,torso,ho.x,ho.y,ho.z,0,light);
        renderAnimalPart(poseStack,vc,hat,head,torso,hato.x,hato.y,hato.z,0,light);
        renderAnimalPart(poseStack,vc,la,larm,torso,lao.x,lao.y,lao.z,0,light);
        renderAnimalPart(poseStack,vc,ra,rarm,torso,rao.x,rao.y,rao.z,0,light);
        renderAnimalPart(poseStack,vc,ll,lleg,torso,llo.x,llo.y,llo.z,0,light);
        renderAnimalPart(poseStack,vc,rl,rleg,torso,rlo.x,rlo.y,rlo.z,0,light);
    }

    private static void renderCamel(ClientRagdoll ragdoll,PoseStack ps,VertexConsumer vc,int light,
            RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart body=camelRoot.getChild("body"),hp=body.getChild("head");
        ModelPart lh=camelRoot.getChild("left_hind_leg"),rh=camelRoot.getChild("right_hind_leg");
        ModelPart lf=camelRoot.getChild("left_front_leg"),rf=camelRoot.getChild("right_front_leg");
        boolean saddle=ragdoll.isChargedCreeper(); body.getChild("saddle").visible=saddle;
        hp.getChild("bridle").visible=saddle; hp.getChild("reins").visible=false;
        float s=ragdoll.isBabyCamel()?.45f:1f;
        org.joml.Vector3f bo=setPosForPart(body,0),ho=setPosForPart(hp,0),lho=setPosForPart(lh,0),rho=setPosForPart(rh,0),lfo=setPosForPart(lf,0),rfo=setPosForPart(rf,0);
        hp.visible=false; renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,0,light,s); hp.visible=true;
        renderAnimalPart(ps,vc,hp,head,torso,ho.x,ho.y,ho.z,0,light,s);
        renderAnimalPart(ps,vc,lh,ll,torso,lho.x,lho.y,lho.z,0,light,s);renderAnimalPart(ps,vc,rh,rl,torso,rho.x,rho.y,rho.z,0,light,s);
        renderAnimalPart(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,0,light,s);renderAnimalPart(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,0,light,s);
    }

    private static void renderLlama(ClientRagdoll ragdoll,PoseStack ps,VertexConsumer vc,int light,
            RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        renderLlamaRoot(ragdoll,llamaRoot,ps,vc,light,torso,head,la,ra,ll,rl);
    }

    private static void renderLlamaRoot(ClientRagdoll ragdoll,ModelPart modelRoot,PoseStack ps,VertexConsumer vc,int light,
            RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart body=modelRoot.getChild("body"),hp=modelRoot.getChild("head");
        ModelPart lh=modelRoot.getChild("left_hind_leg"),rh=modelRoot.getChild("right_hind_leg"),lf=modelRoot.getChild("left_front_leg"),rf=modelRoot.getChild("right_front_leg");
        ModelPart lc=modelRoot.getChild("left_chest"),rc=modelRoot.getChild("right_chest");
        org.joml.Vector3f bo=setPosForPart(body,(float)Math.PI/2),ho=setPosForPart(hp,0),lho=setPosForPart(lh,0),rho=setPosForPart(rh,0),lfo=setPosForPart(lf,0),rfo=setPosForPart(rf,0);
        if(!ragdoll.isBabyLlama()){
            renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,(float)Math.PI/2,light);
            renderAnimalPart(ps,vc,hp,head,torso,ho.x,ho.y,ho.z,0,light);
            renderAnimalPart(ps,vc,lh,ll,torso,lho.x,lho.y,lho.z,0,light);renderAnimalPart(ps,vc,rh,rl,torso,rho.x,rho.y,rho.z,0,light);
            renderAnimalPart(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,0,light);renderAnimalPart(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,0,light);
            if(ragdoll.wasSheared()){
                renderAnimalPartRotated(ps,vc,lc,torso,torso,5.5f,-4f,2f,0,(float)Math.PI/2,0,light,1,1,1);
                renderAnimalPartRotated(ps,vc,rc,torso,torso,-8.5f,-4f,2f,0,(float)Math.PI/2,0,light,1,1,1);
            }
        }else{
            renderAnimalPartScaledXYZ(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,(float)Math.PI/2,light,.625f,.45454544f,.45454544f);
            renderAnimalPartScaledXYZ(ps,vc,hp,head,torso,ho.x,ho.y,ho.z,0,light,.71428573f,.64935064f,.7936508f);
            renderAnimalPartScaledXYZ(ps,vc,lh,ll,torso,lho.x,lho.y,lho.z,0,light,.45454544f,.41322312f,.45454544f);
            renderAnimalPartScaledXYZ(ps,vc,rh,rl,torso,rho.x,rho.y,rho.z,0,light,.45454544f,.41322312f,.45454544f);
            renderAnimalPartScaledXYZ(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,0,light,.45454544f,.41322312f,.45454544f);
            renderAnimalPartScaledXYZ(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,0,light,.45454544f,.41322312f,.45454544f);
        }
    }

    private static void renderRabbit(ClientRagdoll ragdoll,PoseStack ps,VertexConsumer vc,int light,
            RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart body=rabbitRoot.getChild("body"),tail=rabbitRoot.getChild("tail"),hp=rabbitRoot.getChild("head"),le=rabbitRoot.getChild("left_ear"),re=rabbitRoot.getChild("right_ear"),nose=rabbitRoot.getChild("nose");
        ModelPart lh=rabbitRoot.getChild("left_haunch"),rh=rabbitRoot.getChild("right_haunch"),lf=rabbitRoot.getChild("left_front_leg"),rf=rabbitRoot.getChild("right_front_leg"),lfoot=rabbitRoot.getChild("left_hind_foot"),rfoot=rabbitRoot.getChild("right_hind_foot");
        float bs=ragdoll.isBabyRabbit()?.4f:.6f,hs=ragdoll.isBabyRabbit()?.5666667f:.6f;
        org.joml.Vector3f bo=setPosForPart(body,-.34906584f),lfo=setPosForPart(lf,-.1919862f),rfo=setPosForPart(rf,-.1919862f);
        renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,-.34906584f,light,bs);
        renderAnimalPartRotated(ps,vc,tail,torso,torso,0,2.24f,3.87f,-.3490659f,0,0,light,bs,bs,bs);
        renderAnimalPartRotated(ps,vc,hp,head,torso,0,4.5f,2.5f,0,0,0,light,hs,hs,hs);
        renderAnimalPartRotated(ps,vc,le,head,torso,0,4.5f,2.5f,0,.2617994f,0,light,hs,hs,hs);
        renderAnimalPartRotated(ps,vc,re,head,torso,0,4.5f,2.5f,0,-.2617994f,0,light,hs,hs,hs);
        renderAnimalPartRotated(ps,vc,nose,head,torso,0,4.5f,2.5f,0,0,0,light,hs,hs,hs);
        renderAnimalPartRotated(ps,vc,lh,ll,torso,0,-3.25f,-.7f,-.366519f,0,0,light,bs,bs,bs);
        renderAnimalPartRotated(ps,vc,lfoot,ll,torso,0,-3.25f,-.7f,0,0,0,light,bs,bs,bs);
        renderAnimalPartRotated(ps,vc,rh,rl,torso,0,-3.25f,-.7f,-.366519f,0,0,light,bs,bs,bs);
        renderAnimalPartRotated(ps,vc,rfoot,rl,torso,0,-3.25f,-.7f,0,0,0,light,bs,bs,bs);
        renderAnimalPart(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,-.1919862f,light,bs);renderAnimalPart(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,-.1919862f,light,bs);
    }

    private static void renderFrog(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart root=frogRoot.getChild("root"),body=root.getChild("body"),hp=body.getChild("head"),lf=body.getChild("left_arm"),rf=body.getChild("right_arm"),lh=root.getChild("left_leg"),rh=root.getChild("right_leg");
        ModelPart tongue=body.getChild("tongue"),croak=body.getChild("croaking_body");
        org.joml.Vector3f bo=setPosForPart(body,0),ho=setPosForPart(hp,0),lfo=setPosForPart(lf,0),rfo=setPosForPart(rf,0),lho=setPosForPart(lh,0),rho=setPosForPart(rh,0);
        hp.visible=lf.visible=rf.visible=tongue.visible=croak.visible=false;renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,0,light);hp.visible=lf.visible=rf.visible=true;
        renderAnimalPart(ps,vc,hp,head,torso,ho.x,ho.y,ho.z,0,light);renderAnimalPart(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,0,light);renderAnimalPart(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,0,light);
        renderAnimalPart(ps,vc,lh,ll,torso,lho.x,lho.y,lho.z,0,light);renderAnimalPart(ps,vc,rh,rl,torso,rho.x,rho.y,rho.z,0,light);
    }

    private static void renderHoglin(ClientRagdoll r,PoseStack ps,VertexConsumer vc,int light,
            RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart body=hoglinRoot.getChild("body"),hp=hoglinRoot.getChild("head");
        ModelPart lh=hoglinRoot.getChild("left_hind_leg"),rh=hoglinRoot.getChild("right_hind_leg");
        ModelPart lf=hoglinRoot.getChild("left_front_leg"),rf=hoglinRoot.getChild("right_front_leg");
        float bs=r.isBabyHoglin()?.5f:1f,hs=r.isBabyHoglin()?(1.5f/1.9f):1f,hr=.87266463f;
        org.joml.Vector3f bo=setPosForPart(body,0),ho=setPosForPart(hp,hr),lho=setPosForPart(lh,0),rho=setPosForPart(rh,0),lfo=setPosForPart(lf,0),rfo=setPosForPart(rf,0);
        renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,0,light,bs);
        renderAnimalPart(ps,vc,hp,head,torso,ho.x,ho.y,ho.z,hr,light,hs);
        renderAnimalPart(ps,vc,lh,ll,torso,lho.x,lho.y,lho.z,0,light,bs);renderAnimalPart(ps,vc,rh,rl,torso,rho.x,rho.y,rho.z,0,light,bs);
        renderAnimalPart(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,0,light,bs);renderAnimalPart(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,0,light,bs);
    }

    private static void renderSniffer(ClientRagdoll r,PoseStack ps,VertexConsumer vc,int light,
            RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart root=snifferRoot.getChild("root"),bone=root.getChild("bone"),body=bone.getChild("body"),hp=body.getChild("head");
        ModelPart lh=bone.getChild("left_hind_leg"),rh=bone.getChild("right_hind_leg"),lf=bone.getChild("left_front_leg"),rf=bone.getChild("right_front_leg");
        ModelPart lm=bone.getChild("left_mid_leg"),rm=bone.getChild("right_mid_leg");
        float bs=r.isBabySniffer()?.5f:1f,hs=r.isBabySniffer()?.6f:1f;
        org.joml.Vector3f bo=setPosForPart(body,0),lho=setPosForPart(lh,0),rho=setPosForPart(rh,0),lfo=setPosForPart(lf,0),rfo=setPosForPart(rf,0),lmo=setPosForPart(lm,0),rmo=setPosForPart(rm,0);
        hp.visible=false;renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,0,light,bs);hp.visible=true;
        // Centre the complete head subtree (ears, nose and lower beak), not just its first cube.
        renderAnimalPart(ps,vc,hp,head,torso,0,-2f,10.5f,0,light,hs);
        renderAnimalPart(ps,vc,lh,ll,torso,lho.x,lho.y,lho.z,0,light,bs);renderAnimalPart(ps,vc,rh,rl,torso,rho.x,rho.y,rho.z,0,light,bs);
        renderAnimalPart(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,0,light,bs);renderAnimalPart(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,0,light,bs);
        RagdollTransform lmt=offsetTransform(torso,-.46875f*bs,-.84375f*bs,0),rmt=offsetTransform(torso,.46875f*bs,-.84375f*bs,0);
        renderAnimalPart(ps,vc,lm,lmt,torso,lmo.x,lmo.y,lmo.z,0,light,bs);renderAnimalPart(ps,vc,rm,rmt,torso,rmo.x,rmo.y,rmo.z,0,light,bs);
    }

    private static void renderRavager(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart body=ravagerRoot.getChild("body"),neck=ravagerRoot.getChild("neck");
        ModelPart lh=ravagerRoot.getChild("left_hind_leg"),rh=ravagerRoot.getChild("right_hind_leg"),lf=ravagerRoot.getChild("left_front_leg"),rf=ravagerRoot.getChild("right_front_leg");
        org.joml.Vector3f bo=setPosForPart(body,(float)Math.PI/2),lho=setPosForPart(lh,0),rho=setPosForPart(rh,0),lfo=setPosForPart(lf,0),rfo=setPosForPart(rf,0);
        renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,(float)Math.PI/2,light);
        // Neck-local centre of the main head cube is (0,6,-23).
        renderAnimalPart(ps,vc,neck,head,torso,0,-6f,23f,0,light);
        renderAnimalPart(ps,vc,lh,ll,torso,lho.x,lho.y,lho.z,0,light);renderAnimalPart(ps,vc,rh,rl,torso,rho.x,rho.y,rho.z,0,light);
        renderAnimalPart(ps,vc,lf,la,torso,lfo.x,lfo.y,lfo.z,0,light);renderAnimalPart(ps,vc,rf,ra,torso,rfo.x,rfo.y,rfo.z,0,light);
    }

    private static void renderPhantom(ClientRagdoll r,PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform lw,RagdollTransform rw,RagdollTransform tb,RagdollTransform tt){
        ModelPart body=phantomRoot.getChild("body"),hp=body.getChild("head"),left=body.getChild("left_wing_base"),right=body.getChild("right_wing_base"),tail=body.getChild("tail_base"),tip=tail.getChild("tail_tip");
        float s=r.getPhantomRenderScale();
        hp.visible=left.visible=right.visible=tail.visible=false;
        org.joml.Vector3f bo=setPosForPart(body,-.1f);renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,-.1f,light,s);
        hp.visible=left.visible=right.visible=tail.visible=true;
        renderAnimalPart(ps,vc,hp,head,torso,setPosForPart(hp,.2f).x,setPosForPart(hp,.2f).y,setPosForPart(hp,.2f).z,.2f,light,s);
        renderAnimalPartRotated(ps,vc,left,lw,torso,-9.5f,-1f,-4.5f,0,0,.1f,light,s,s,s);
        renderAnimalPartRotated(ps,vc,right,rw,torso,9.5f,-1f,-4.5f,0,0,-.1f,light,s,s,s);
        tip.visible=false;renderAnimalPart(ps,vc,tail,tb,torso,.5f,-1f,-3f,0,light,s);tip.visible=true;
        renderAnimalPart(ps,vc,tip,tt,torso,.5f,-.5f,-3f,0,light,s);
    }

    private static void renderParrot(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform lw,RagdollTransform rw,RagdollTransform ll,RagdollTransform rl){
        ModelPart body=parrotRoot.getChild("body"),hp=parrotRoot.getChild("head"),left=parrotRoot.getChild("left_wing"),right=parrotRoot.getChild("right_wing"),lleg=parrotRoot.getChild("left_leg"),rleg=parrotRoot.getChild("right_leg"),tail=parrotRoot.getChild("tail");
        org.joml.Vector3f bo=setPosForPart(body,.4937f),ho=setPosForPart(hp,0),lo=setPosForPart(left,-.6981f),ro=setPosForPart(right,-.6981f),llo=setPosForPart(lleg,-.0299f),rlo=setPosForPart(rleg,-.0299f),to=setPosForPart(tail,1.015f);
        renderAnimalPart(ps,vc,body,torso,torso,bo.x,bo.y,bo.z,.4937f,light);renderAnimalPart(ps,vc,hp,head,torso,ho.x,ho.y,ho.z,0,light);
        renderAnimalPartRotated(ps,vc,left,lw,torso,lo.x,lo.y,lo.z,-.6981f,(float)Math.PI,0,light,1,1,1);renderAnimalPartRotated(ps,vc,right,rw,torso,ro.x,ro.y,ro.z,-.6981f,(float)Math.PI,0,light,1,1,1);
        renderAnimalPart(ps,vc,lleg,ll,torso,llo.x,llo.y,llo.z,-.0299f,light);renderAnimalPart(ps,vc,rleg,rl,torso,rlo.x,rlo.y,rlo.z,-.0299f,light);
        renderAnimalPart(ps,vc,tail,offsetTransform(torso,0,-.160625f,.22875f),torso,to.x,to.y,to.z,1.015f,light);
    }

    private static void renderSlime(ClientRagdoll r,PoseStack ps,MultiBufferSource buffer,int light,RagdollTransform torso){
        ResourceLocation texture=getMobTexture(r);
        renderAnimalPartRotated(ps,buffer.getBuffer(RenderType.entityCutoutNoCull(texture)),slimeInnerRoot,torso,torso,0,-20,0,0,0,0,light,1,1,1);
        renderAnimalPartRotated(ps,buffer.getBuffer(RenderType.entityTranslucent(texture)),slimeOuterRoot,torso,torso,0,-20,0,0,0,0,light,1,1,1);
    }

    private static void renderMagmaCube(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso){
        renderAnimalPartRotated(ps,vc,magmaCubeRoot,torso,torso,0,-20,0,0,0,0,15728880,1,1,1);
    }

    private static RagdollTransform offsetTransform(RagdollTransform base,float x,float y,float z){
        org.joml.Vector3f off=new org.joml.Vector3f(x,y,z);
        new Quaternionf(base.rotation.x,base.rotation.y,base.rotation.z,base.rotation.w).transform(off);
        return new RagdollTransform(base.partId,base.position.x+off.x,base.position.y+off.y,base.position.z+off.z,
                base.rotation.x,base.rotation.y,base.rotation.z,base.rotation.w);
    }

    private static void renderSilverfish(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform s5,RagdollTransform s6,RagdollTransform s3,RagdollTransform s4){
        ModelPart s0=silverfishRoot.getChild("segment0"),s1=silverfishRoot.getChild("segment1"),s2=silverfishRoot.getChild("segment2"),p3=silverfishRoot.getChild("segment3"),p4=silverfishRoot.getChild("segment4"),p5=silverfishRoot.getChild("segment5"),p6=silverfishRoot.getChild("segment6");
        renderCentered(ps,vc,s0,offsetTransform(head,0,-.03125f,-.0625f),torso,light);renderCentered(ps,vc,s1,offsetTransform(head,0,0,.0625f),torso,light);renderCentered(ps,vc,s2,torso,torso,light);
        renderCentered(ps,vc,p3,s3,torso,light);renderCentered(ps,vc,p4,s4,torso,light);renderCentered(ps,vc,p5,s5,torso,light);renderCentered(ps,vc,p6,s6,torso,light);
        renderCentered(ps,vc,silverfishRoot.getChild("layer0"),offsetTransform(torso,0,.125f,0),torso,light);
        renderCentered(ps,vc,silverfishRoot.getChild("layer1"),offsetTransform(s4,0,.0625f,0),torso,light);
        renderCentered(ps,vc,silverfishRoot.getChild("layer2"),offsetTransform(head,0,.0625f,.0625f),torso,light);
    }

    private static void renderEndermite(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform unused1,RagdollTransform unused2,RagdollTransform s2,RagdollTransform s3){
        renderCentered(ps,vc,endermiteRoot.getChild("segment0"),head,torso,light);renderCentered(ps,vc,endermiteRoot.getChild("segment1"),torso,torso,light);
        renderCentered(ps,vc,endermiteRoot.getChild("segment2"),s2,torso,light);renderCentered(ps,vc,endermiteRoot.getChild("segment3"),s3,torso,light);
    }

    private static void renderAllay(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform lw,RagdollTransform rw){
        allayRoot.getAllParts().forEach(ModelPart::resetPose);
        ModelPart root=allayRoot.getChild("root"),body=root.getChild("body"),hp=root.getChild("head"),leftArm=body.getChild("left_arm"),rightArm=body.getChild("right_arm"),leftWing=body.getChild("left_wing"),rightWing=body.getChild("right_wing");
        org.joml.Vector3f bodyCenter=cubeBoxCenter(body);
        leftArm.visible=rightArm.visible=leftWing.visible=rightWing.visible=false;renderCentered(ps,vc,body,torso,torso,light);leftArm.visible=rightArm.visible=leftWing.visible=rightWing.visible=true;
        renderCentered(ps,vc,hp,head,torso,light);renderCentered(ps,vc,leftArm,la,torso,light);renderCentered(ps,vc,rightArm,ra,torso,light);
        renderPropPart(ps,vc,leftWing,torso,torso,bodyCenter.x,bodyCenter.y,bodyCenter.z,1,.436332f,(float)Math.PI/4,0,light);
        renderPropPart(ps,vc,rightWing,torso,torso,bodyCenter.x,bodyCenter.y,bodyCenter.z,1,.436332f,-(float)Math.PI/4,0,light);
    }

    private static void renderStrider(ClientRagdoll r,ModelPart root,PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform ll,RagdollTransform rl){
        float s=r.isBabyStrider()?.5f:1f;ModelPart body=root.getChild("body"),left=root.getChild("left_leg"),right=root.getChild("right_leg");
        renderCenteredScaled(ps,vc,body,torso,torso,light,s);renderCenteredScaled(ps,vc,left,ll,torso,light,s);renderCenteredScaled(ps,vc,right,rl,torso,light,s);
    }

    private static void renderSnowGolem(ClientRagdoll ragdoll,PoseStack ps,MultiBufferSource buffer,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform lower){
        snowGolemRoot.getAllParts().forEach(ModelPart::resetPose);
        ModelPart upper=snowGolemRoot.getChild("upper_body");org.joml.Vector3f upperCenter=cubeBoxCenter(upper);float baseX=upper.x+upperCenter.x,baseY=upper.y+upperCenter.y,baseZ=upper.z+upperCenter.z;
        renderCentered(ps,vc,upper,torso,torso,light);renderCentered(ps,vc,snowGolemRoot.getChild("head"),head,torso,light);renderCentered(ps,vc,snowGolemRoot.getChild("lower_body"),lower,torso,light);
        ModelPart l=snowGolemRoot.getChild("left_arm"),r=snowGolemRoot.getChild("right_arm");
        renderPropPart(ps,vc,l,torso,torso,baseX,baseY,baseZ,1,0,0,1f,light);
        renderPropPart(ps,vc,r,torso,torso,baseX,baseY,baseZ,1,0,(float)Math.PI,-1f,light);
        if (ragdoll.wasSheared() && head != null) {
            ps.pushPose();
            try {
                ps.translate(head.position.x-torso.position.x,head.position.y-torso.position.y,head.position.z-torso.position.z);
                tempQuat.set(head.rotation.x,head.rotation.y,head.rotation.z,head.rotation.w).rotateZ((float)Math.PI);
                ps.mulPose(tempQuat);
                // SnowGolemHeadLayer renders a 10px-wide block around the 7px head. The
                // negative Y/Z scale and half-block translation preserve the carved face's
                // vanilla orientation while the whole layer follows the physics head.
                ps.mulPose(new Quaternionf().rotateY((float)Math.PI));
                ps.scale(.625f,-.625f,-.625f);
                ps.translate(-.5f,-.5f,-.5f);
                Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                        Blocks.CARVED_PUMPKIN.defaultBlockState(),ps,buffer,light,OverlayTexture.NO_OVERLAY);
            } finally {
                ps.popPose();
            }
        }
    }

    private static void renderBlaze(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform g0,RagdollTransform g1,RagdollTransform g2,RagdollTransform g3,RagdollTransform g4){
        blazeRoot.getAllParts().forEach(ModelPart::resetPose);ModelPart hp=blazeRoot.getChild("head");org.joml.Vector3f hc=cubeBoxCenter(hp);float bx=hp.x+hc.x,by=hp.y+hc.y,bz=hp.z+hc.z;
        renderCentered(ps,vc,hp,torso,torso,light);
        for(int i=0;i<12;i++) renderPropPart(ps,vc,blazeRoot.getChild("part"+i),torso,torso,bx,by,bz,1,0,0,0,light);
    }

    private static void renderSpider(ClientRagdoll r,PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform lf,RagdollTransform rf,RagdollTransform lh,RagdollTransform rh){
        boolean cave=isExactMobPath(r.getMobType(),"cave_spider");float s=cave?.7f:1f;ModelPart root=cave?caveSpiderRoot:spiderRoot;
        root.getAllParts().forEach(ModelPart::resetPose);
        renderCenteredScaled(ps,vc,root.getChild("body0"),offsetTransform(torso,0,0,-.375f*s),torso,light,s);renderCenteredScaled(ps,vc,root.getChild("body1"),offsetTransform(torso,0,0,.1875f*s),torso,light,s);renderCenteredScaled(ps,vc,root.getChild("head"),head,torso,light,s);
        String[] names={"left_hind_leg","right_hind_leg","left_middle_hind_leg","right_middle_hind_leg","left_middle_front_leg","right_middle_front_leg","left_front_leg","right_front_leg"};
        for(int i=0;i<8;i++) renderCenteredScaled(ps,vc,root.getChild(names[i]),r.getSmoothedTransform(i+2),torso,light,s);
    }

    private static void renderShulker(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform lid){
        renderCentered(ps,vc,shulkerRoot.getChild("base"),torso,torso,light);
        renderCentered(ps,vc,shulkerRoot.getChild("lid"),lid,torso,light);
        renderCentered(ps,vc,shulkerRoot.getChild("head"),lid,torso,light);
    }

    private static void renderGhast(ClientRagdoll ragdoll,PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso){
        ghastRoot.getAllParts().forEach(ModelPart::resetPose);ModelPart body=ghastRoot.getChild("body");
        renderCenteredScaled(ps,vc,body,torso,torso,light,4.5f);
        for(int i=0;i<9;i++) renderCenteredScaled(ps,vc,ghastRoot.getChild("tentacle"+i),ragdoll.getSmoothedTransform(i+1),torso,light,4.5f);
    }

    private static void renderVex(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra){
        vexRoot.getAllParts().forEach(ModelPart::resetPose);
        ModelPart root=vexRoot.getChild("root"),body=root.getChild("body"),hp=root.getChild("head"),leftArm=body.getChild("left_arm"),rightArm=body.getChild("right_arm"),leftWing=body.getChild("left_wing"),rightWing=body.getChild("right_wing");
        org.joml.Vector3f bodyCenter=cubeBoxCenter(body);
        leftArm.visible=rightArm.visible=leftWing.visible=rightWing.visible=false;renderCentered(ps,vc,body,torso,torso,light);leftArm.visible=rightArm.visible=leftWing.visible=rightWing.visible=true;
        renderCentered(ps,vc,hp,head,torso,light);renderCentered(ps,vc,leftArm,la,torso,light);renderCentered(ps,vc,rightArm,ra,torso,light);
        renderPropPart(ps,vc,leftWing,torso,torso,bodyCenter.x,bodyCenter.y,bodyCenter.z,1,.47123888f,1.0995574f,-.47123888f,light);
        renderPropPart(ps,vc,rightWing,torso,torso,bodyCenter.x,bodyCenter.y,bodyCenter.z,1,.47123888f,-1.0995574f,.47123888f,light);
    }

    private static void renderWarden(PoseStack ps,VertexConsumer vc,int light,RagdollTransform torso,RagdollTransform head,RagdollTransform la,RagdollTransform ra,RagdollTransform ll,RagdollTransform rl){
        ModelPart bone=wardenRoot.getChild("bone"),body=bone.getChild("body"),hp=body.getChild("head"),leftArm=body.getChild("left_arm"),rightArm=body.getChild("right_arm");
        hp.visible=leftArm.visible=rightArm.visible=false;renderCentered(ps,vc,body,torso,torso,light);hp.visible=leftArm.visible=rightArm.visible=true;
        renderCentered(ps,vc,hp,head,torso,light);renderCentered(ps,vc,leftArm,la,torso,light);renderCentered(ps,vc,rightArm,ra,torso,light);
        renderCentered(ps,vc,bone.getChild("left_leg"),ll,torso,light);renderCentered(ps,vc,bone.getChild("right_leg"),rl,torso,light);
    }

    private static void renderPropPart(PoseStack ps,VertexConsumer vc,ModelPart part,RagdollTransform parent,RagdollTransform torso,float baseX,float baseY,float baseZ,float scale,float xRot,float yRot,float zRot,int light){
        if(parent==null)return;ps.pushPose();
        try{
            ps.translate(parent.position.x-torso.position.x,parent.position.y-torso.position.y,parent.position.z-torso.position.z);
            tempQuat.set(parent.rotation.x,parent.rotation.y,parent.rotation.z,parent.rotation.w).rotateZ((float)Math.PI);ps.mulPose(tempQuat);
            ps.scale(scale,scale,scale);ps.translate(-baseX/16f,-baseY/16f,-baseZ/16f);
            part.xRot=xRot;part.yRot=yRot;part.zRot=zRot;part.render(ps,vc,light,OverlayTexture.NO_OVERLAY);
        }finally{ps.popPose();}
    }

    private static void renderCentered(PoseStack ps,VertexConsumer vc,ModelPart part,RagdollTransform tr,RagdollTransform torso,int light){org.joml.Vector3f o=setPosForPart(part,0);renderAnimalPart(ps,vc,part,tr,torso,o.x,o.y,o.z,0,light);}
    private static void renderCenteredScaled(PoseStack ps,VertexConsumer vc,ModelPart part,RagdollTransform tr,RagdollTransform torso,int light,float s){org.joml.Vector3f o=setPosForPart(part,0);renderAnimalPart(ps,vc,part,tr,torso,o.x,o.y,o.z,0,light,s);}

    private static ModelPart equineRoot(String mobType) {
        if (isExactMobPath(mobType, "donkey")) return donkeyRoot;
        if (isExactMobPath(mobType, "mule")) return muleRoot;
        if (isExactMobPath(mobType, "skeleton_horse")) return skeletonHorseRoot;
        if (isExactMobPath(mobType, "zombie_horse")) return zombieHorseRoot;
        return horseRoot;
    }

    private static float equineRenderScale(String mobType) {
        if (isExactMobPath(mobType, "horse")) return 1.1f;
        if (isExactMobPath(mobType, "donkey")) return 0.87f;
        if (isExactMobPath(mobType, "mule")) return 0.92f;
        return 1.0f;
    }

    private static void renderEquineParts(ClientRagdoll ragdoll, ModelPart root,
                                           PoseStack poseStack, VertexConsumer vc, int light,
                                           RagdollTransform torso, RagdollTransform head,
                                           RagdollTransform larm, RagdollTransform rarm,
                                           RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart body = root.getChild("body");
        ModelPart headParts = root.getChild("head_parts");
        ModelPart adultLH = root.getChild("left_hind_leg");
        ModelPart adultRH = root.getChild("right_hind_leg");
        ModelPart adultLF = root.getChild("left_front_leg");
        ModelPart adultRF = root.getChild("right_front_leg");
        ModelPart babyLH = root.getChild("left_hind_baby_leg");
        ModelPart babyRH = root.getChild("right_hind_baby_leg");
        ModelPart babyLF = root.getChild("left_front_baby_leg");
        ModelPart babyRF = root.getChild("right_front_baby_leg");

        boolean baby = ragdoll.isBabyEquine();
        adultLH.visible = adultRH.visible = adultLF.visible = adultRF.visible = !baby;
        babyLH.visible = babyRH.visible = babyLF.visible = babyRF.visible = baby;

        boolean saddled = ragdoll.isSaddledEquine();
        body.getChild("saddle").visible = saddled;
        headParts.getChild("left_saddle_mouth").visible = saddled;
        headParts.getChild("right_saddle_mouth").visible = saddled;
        headParts.getChild("head_saddle").visible = saddled;
        headParts.getChild("mouth_saddle_wrap").visible = saddled;
        headParts.getChild("left_saddle_line").visible = false;
        headParts.getChild("right_saddle_line").visible = false;

        if (isExactMobPath(ragdoll.getMobType(), "donkey") || isExactMobPath(ragdoll.getMobType(), "mule")) {
            body.getChild("left_chest").visible = ragdoll.hasEquineChest();
            body.getChild("right_chest").visible = ragdoll.hasEquineChest();
        }

        ModelPart leftHind = baby ? babyLH : adultLH;
        ModelPart rightHind = baby ? babyRH : adultRH;
        ModelPart leftFront = baby ? babyLF : adultLF;
        ModelPart rightFront = baby ? babyRF : adultRF;
        org.joml.Vector3f bodyOff = setPosForPart(body, 0);
        float neckPitch = (float) (Math.PI / 6.0);
        org.joml.Vector3f headOff = setPosForPart(headParts, neckPitch);
        org.joml.Vector3f lhOff = setPosForPart(leftHind, 0);
        org.joml.Vector3f rhOff = setPosForPart(rightHind, 0);
        org.joml.Vector3f lfOff = setPosForPart(leftFront, 0);
        org.joml.Vector3f rfOff = setPosForPart(rightFront, 0);

        float speciesScale = equineRenderScale(ragdoll.getMobType());
        float bodyScale = speciesScale * (baby ? 0.5f : 1.0f);
        float headScale = speciesScale * (baby ? 1.5f / 2.7272f : 1.0f);
        renderAnimalPart(poseStack, vc, body,       torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, headParts,  head,  torso, headOff.x, headOff.y, headOff.z, neckPitch, light, headScale);
        renderAnimalPart(poseStack, vc, leftHind,   lleg,  torso, lhOff.x, lhOff.y, lhOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightHind,  rleg,  torso, rhOff.x, rhOff.y, rhOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, leftFront,  larm,  torso, lfOff.x, lfOff.y, lfOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightFront, rarm,  torso, rfOff.x, rfOff.y, rfOff.z, 0, light, bodyScale);
    }

    private static boolean isExactMobPath(String mobType, String expectedPath) {
        int separator = mobType.indexOf(':');
        int pathStart = separator >= 0 ? separator + 1 : 0;
        return mobType.length() - pathStart == expectedPath.length()
                && mobType.regionMatches(pathStart, expectedPath, 0, expectedPath.length());
    }

    private static void renderChicken(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                       RagdollTransform torso, RagdollTransform head,
                                       RagdollTransform larm, RagdollTransform rarm,
                                       RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = chickenRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart beak = root.getChild("beak");
        ModelPart redThing = root.getChild("red_thing");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        ModelPart leftWing = root.getChild("left_wing");
        ModelPart rightWing = root.getChild("right_wing");

        float halfPI = (float) (Math.PI / 2);
        // beak and redThing share the head's pivot in the vanilla model — their cubes are
        // designed at offsets relative to the same pivot as head, so they piggyback on
        // the head's setPos rather than computing their own.
        org.joml.Vector3f bodyOff      = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff      = setPosForPart(headPart, 0);
        org.joml.Vector3f leftLegOff   = setPosForPart(leftLeg, 0);
        org.joml.Vector3f rightLegOff  = setPosForPart(rightLeg, 0);
        org.joml.Vector3f leftWingOff  = setPosForPart(leftWing, 0);
        org.joml.Vector3f rightWingOff = setPosForPart(rightWing, 0);

        float bodyScale = ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f;
        renderAnimalPart(poseStack, vc, body,      torso, torso, bodyOff.x,      bodyOff.y,      bodyOff.z,      halfPI, light, bodyScale);
        renderAnimalPart(poseStack, vc, headPart,  head,  torso, headOff.x,      headOff.y,      headOff.z,      0,      light);
        renderAnimalPart(poseStack, vc, beak,      head,  torso, headOff.x,      headOff.y,      headOff.z,      0,      light);
        renderAnimalPart(poseStack, vc, redThing,  head,  torso, headOff.x,      headOff.y,      headOff.z,      0,      light);
        renderAnimalPart(poseStack, vc, leftLeg,   lleg,  torso, leftLegOff.x,   leftLegOff.y,   leftLegOff.z,   0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightLeg,  rleg,  torso, rightLegOff.x,  rightLegOff.y,  rightLegOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, leftWing,  larm,  torso, leftWingOff.x,  leftWingOff.y,  leftWingOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightWing, rarm,  torso, rightWingOff.x, rightWingOff.y, rightWingOff.z, 0,      light, bodyScale);
    }

    // Cat and ocelot: the same six-body quadruped layout as cows and pigs, on OcelotModel
    // geometry, with two tail segments anchored to the torso so the tail tumbles with the body.
    // The model's front and hind legs differ in length, and setPosForPart centres each rendered
    // leg cube on its own physics body, so the visuals follow the real cube sizes regardless of
    // the near-uniform physics boxes.
    private static void renderCat(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                  RagdollTransform torso, RagdollTransform head,
                                  RagdollTransform larm, RagdollTransform rarm,
                                  RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = catRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");
        ModelPart tail1 = root.getChild("tail1");
        ModelPart tail2 = root.getChild("tail2");

        float halfPI = (float) (Math.PI / 2);
        org.joml.Vector3f bodyOff       = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff       = setPosForPart(headPart, 0);
        org.joml.Vector3f leftHindOff   = setPosForPart(leftHind, 0);
        org.joml.Vector3f rightHindOff  = setPosForPart(rightHind, 0);
        org.joml.Vector3f leftFrontOff  = setPosForPart(leftFront, 0);
        org.joml.Vector3f rightFrontOff = setPosForPart(rightFront, 0);

        // CatRenderer draws the model at 0.8×. Kittens are NOT a uniform shrink: OcelotModel has
        // scaleHead=true (babyHeadScale=2), so the head renders at 1.5/2 = 0.75× and the body at
        // 1.0/2 = 0.5× (AgeableListModel). Adults use 1.0× for both. Everything then ×0.8.
        boolean baby = ragdoll.usesBabyBodyScale();
        float bodyScale = (baby ? 0.5f  : 1.0f) * 0.8f;
        float headScale = (baby ? 0.75f : 1.0f) * 0.8f;
        renderAnimalPart(poseStack, vc, body,       torso, torso, bodyOff.x,       bodyOff.y,       bodyOff.z,       halfPI, light, bodyScale);
        renderAnimalPart(poseStack, vc, headPart,   head,  torso, headOff.x,       headOff.y,       headOff.z,       0,      light, headScale);
        renderAnimalPart(poseStack, vc, leftHind,   lleg,  torso, leftHindOff.x,   leftHindOff.y,   leftHindOff.z,   0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightHind,  rleg,  torso, rightHindOff.x,  rightHindOff.y,  rightHindOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, leftFront,  larm,  torso, leftFrontOff.x,  leftFrontOff.y,  leftFrontOff.z,  0,      light, bodyScale);
        renderAnimalPart(poseStack, vc, rightFront, rarm,  torso, rightFrontOff.x, rightFrontOff.y, rightFrontOff.z, 0,      light, bodyScale);
        // Tail segments ride the torso. setPos = part pivot − body-cube centre (root coords),
        // matching how the villager arms unit is anchored; their resting xRot mirrors vanilla.
        renderAnimalPart(poseStack, vc, tail1, torso, torso, 0, -2, 7,  0.9f,      light, bodyScale);
        renderAnimalPart(poseStack, vc, tail2, torso, torso, 0,  3, 13, 1.7278761f, light, bodyScale);
    }

    // Dyed collar overlay for a tamed cat: renderCat's body, head, and leg placement at the same
    // 0.8x scale on CAT_COLLAR geometry, tinted by the dye colour carried in dyeColorId. The
    // tail is skipped, since the collar texture is empty there.
    private static void renderCatCollar(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer, int light,
                                        RagdollTransform torso, RagdollTransform head,
                                        RagdollTransform larm, RagdollTransform rarm,
                                        RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = catCollarRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart rightHind = root.getChild("right_hind_leg");
        ModelPart leftHind = root.getChild("left_hind_leg");
        ModelPart rightFront = root.getChild("right_front_leg");
        ModelPart leftFront = root.getChild("left_front_leg");

        net.minecraft.world.item.DyeColor color =
                net.minecraft.world.item.DyeColor.byId(ragdoll.getDyeColorId() & 0xF);
        float[] rgb = color.getTextureDiffuseColors();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(CAT_COLLAR_TEXTURE));

        float halfPI = (float) (Math.PI / 2);
        org.joml.Vector3f bodyOff       = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff       = setPosForPart(headPart, 0);
        org.joml.Vector3f leftHindOff   = setPosForPart(leftHind, 0);
        org.joml.Vector3f rightHindOff  = setPosForPart(rightHind, 0);
        org.joml.Vector3f leftFrontOff  = setPosForPart(leftFront, 0);
        org.joml.Vector3f rightFrontOff = setPosForPart(rightFront, 0);

        boolean baby = ragdoll.usesBabyBodyScale();
        float bodyScale = (baby ? 0.5f  : 1.0f) * 0.8f;
        float headScale = (baby ? 0.75f : 1.0f) * 0.8f;
        renderAnimalPartTinted(poseStack, vc, body,       torso, torso, bodyOff.x,       bodyOff.y,       bodyOff.z,       halfPI, light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, headPart,   head,  torso, headOff.x,       headOff.y,       headOff.z,       0,      light, rgb[0], rgb[1], rgb[2], headScale);
        renderAnimalPartTinted(poseStack, vc, leftHind,   lleg,  torso, leftHindOff.x,   leftHindOff.y,   leftHindOff.z,   0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, rightHind,  rleg,  torso, rightHindOff.x,  rightHindOff.y,  rightHindOff.z,  0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, leftFront,  larm,  torso, leftFrontOff.x,  leftFrontOff.y,  leftFrontOff.z,  0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
        renderAnimalPartTinted(poseStack, vc, rightFront, rarm,  torso, rightFrontOff.x, rightFrontOff.y, rightFrontOff.z, 0,      light, rgb[0], rgb[1], rgb[2], bodyScale);
    }

    // Bat: torso is the main body with the lower membrane hanging below as in vanilla, head
    // above, the two wings in the arm slots, and the two membrane tips in the leg slots. Wings
    // and tips are drawn detached from the vanilla parent/child hierarchy so each rides its own
    // physics body, with visibility toggled so nothing draws twice.
    private static void renderBat(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                  RagdollTransform torso, RagdollTransform head,
                                  RagdollTransform larm, RagdollTransform rarm,
                                  RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart root = batRoot;
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart leftWing = body.getChild("left_wing");
        ModelPart rightWing = body.getChild("right_wing");
        ModelPart leftTip = leftWing.getChild("left_wing_tip");
        ModelPart rightTip = rightWing.getChild("right_wing_tip");

        // BatRenderer draws the model at 0.35×; the physics bodies are authored to match.
        final float bs = 0.35f;
        org.joml.Vector3f headOff = setPosForPart(headPart, 0);
        org.joml.Vector3f lwOff = setPosForPart(leftWing, 0);
        org.joml.Vector3f rwOff = setPosForPart(rightWing, 0);

        try {
            // Draw the body (main cube + membrane) without the wings, which ride their own bodies.
            // The membrane is part of "body"; centre the MAIN 6x12x6 cube (centre y=10) so it
            // hangs below exactly like vanilla — setPosForPart can't be used as it would average
            // in the membrane cube.
            leftWing.visible = false; rightWing.visible = false;
            renderAnimalPart(poseStack, vc, body, torso, torso, 0, -10, 0, 0, light, bs);
            renderAnimalPart(poseStack, vc, headPart, head, torso, headOff.x, headOff.y, headOff.z, 0, light, bs);

            // Each wing is one rigid body: draw the wing with its tip (the wing's child) still
            // visible so wing + membrane tip move together.
            leftWing.visible = true; rightWing.visible = true;
            renderAnimalPart(poseStack, vc, leftWing, larm, torso, lwOff.x, lwOff.y, lwOff.z, 0, light, bs);
            renderAnimalPart(poseStack, vc, rightWing, rarm, torso, rwOff.x, rwOff.y, rwOff.z, 0, light, bs);
        } finally {
            leftWing.visible = true; rightWing.visible = true;
            leftTip.visible = true; rightTip.visible = true;
        }
    }

    // Bee: torso is the body box, drawing antennae and stinger as its children, with the two
    // flat wings in the arm slots and the leg strips grouped into the leg slots — front and
    // middle left, back right. No separate head model part exists, so the head stub is not drawn.
    private static void renderBee(ClientRagdoll ragdoll, PoseStack poseStack, VertexConsumer vc, int light,
                                  RagdollTransform torso, RagdollTransform head,
                                  RagdollTransform larm, RagdollTransform rarm,
                                  RagdollTransform lleg, RagdollTransform rleg) {
        ModelPart bone = beeRoot.getChild("bone");
        ModelPart body = bone.getChild("body");
        ModelPart leftWing = bone.getChild("left_wing");
        ModelPart rightWing = bone.getChild("right_wing");
        ModelPart frontLegs = bone.getChild("front_legs");
        ModelPart middleLegs = bone.getChild("middle_legs");
        ModelPart backLegs = bone.getChild("back_legs");

        org.joml.Vector3f bodyOff = setPosForPart(body, 0);
        org.joml.Vector3f lwOff = setPosForPart(leftWing, 0);
        org.joml.Vector3f rwOff = setPosForPart(rightWing, 0);

        // Baby bees render at half scale (vanilla). Wings ride their own bodies; the tiny flat
        // leg strips stay glued to the body, so they're anchored to the torso via
        // setPos = part pivot − body-cube centre (bone-space), like the cat tail / villager arms.
        float bodyScale = ragdoll.isBabyBee() ? 0.5f : 1.0f;
        renderAnimalPart(poseStack, vc, body, torso, torso, bodyOff.x, bodyOff.y, bodyOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, leftWing, larm, torso, lwOff.x, lwOff.y, lwOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, rightWing, rarm, torso, rwOff.x, rwOff.y, rwOff.z, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, frontLegs,  torso, torso, 1.5f, 3.5f, -2f, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, middleLegs, torso, torso, 1.5f, 3.5f,  0f, 0, light, bodyScale);
        renderAnimalPart(poseStack, vc, backLegs,   torso, torso, 1.5f, 3.5f,  2f, 0, light, bodyScale);
    }

    private static void renderMobVanillaArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                               int light, RagdollTransform torso, RagdollTransform head,
                                               RagdollTransform larm, RagdollTransform rarm,
                                               RagdollTransform lleg, RagdollTransform rleg, HumanoidScale modelScale) {
        // No real entity for mobs here; resolveArmorModel falls back to the proxy ArmorStand.
        renderVanillaArmorSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
        renderVanillaArmorSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
        renderVanillaArmorSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
        renderVanillaArmorSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, mobArmorInner, mobArmorOuter, null, modelScale);
    }

    private static void renderMobGeckoLibArmor(ClientRagdoll ragdoll, PoseStack poseStack, MultiBufferSource buffer,
                                                int light, RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg, HumanoidScale modelScale) {
        // GeckoLib uses the proxy ArmorStand inside GeckoLibArmorHelper — no real entity needed
        renderGeckoLibSlot(ragdoll.getHelmet(), EquipmentSlot.HEAD, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
        renderGeckoLibSlot(ragdoll.getChestplate(), EquipmentSlot.CHEST, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
        renderGeckoLibSlot(ragdoll.getLeggings(), EquipmentSlot.LEGS, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
        renderGeckoLibSlot(ragdoll.getBoots(), EquipmentSlot.FEET, poseStack, buffer, light, mobArmorInner, torso, head, larm, rarm, lleg, rleg, null, modelScale);
    }

    // ============================
    // Core render methods
    // ============================

    // Tinted renderHumanoidPartPhysics, for the charged-creeper energy swirl drawn at half RGB.
    private static void renderHumanoidPartPhysicsTinted(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                         RagdollTransform transform, RagdollTransform torso,
                                                         int light, RagdollPart ragdollPart,
                                                         float r, float g, float b, float a) {
        renderHumanoidPartPhysicsTinted(poseStack, vc, part, transform, torso, light, ragdollPart, r, g, b, a, HumanoidScale.ADULT);
    }

    private static void renderHumanoidPartPhysicsTinted(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                         RagdollTransform transform, RagdollTransform torso,
                                                         int light, RagdollPart ragdollPart,
                                                         float r, float g, float b, float a, HumanoidScale scale) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

            switch (ragdollPart) {
                case HEAD:      part.setPos(0, 4, 0);    break;
                case TORSO:     part.setPos(0, -6, 0);   break;
                case LEFT_ARM:  part.setPos(-1, -4, 0);  break;
                case RIGHT_ARM: part.setPos(1, -4, 0);   break;
                case LEFT_LEG:
                case RIGHT_LEG: part.setPos(0, -6, 0);   break;
            }
            part.xRot = 0;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY, r, g, b, a);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderHumanoidPartPhysics(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   int light, RagdollPart ragdollPart) {
        renderHumanoidPartPhysics(poseStack, vc, part, transform, torso, light, ragdollPart, HumanoidScale.ADULT);
    }

    private static void renderHumanoidPartPhysics(PoseStack poseStack, VertexConsumer vc, ModelPart part,
                                                   RagdollTransform transform, RagdollTransform torso,
                                                   int light, RagdollPart ragdollPart, HumanoidScale scale) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            float ms = scale.forPart(ragdollPart);
            if (ms != 1.0f) {
                poseStack.scale(ms, ms, ms);
            }

            switch (ragdollPart) {
                case HEAD:      part.setPos(0, 4, 0);    break;
                case TORSO:     part.setPos(0, -6, 0);   break;
                case LEFT_ARM:  part.setPos(-1, -4, 0);  break;
                case RIGHT_ARM: part.setPos(1, -4, 0);   break;
                case LEFT_LEG:
                case RIGHT_LEG: part.setPos(0, -6, 0);   break;
            }
            part.xRot = 0;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
        } finally {
            poseStack.popPose();
        }
    }

    // Body-versus-head scale for a humanoid ragdoll. Babies are not a uniform shrink: vanilla's
    // young HumanoidModel draws the head at 0.75 and everything else at 0.5, which is where a
    // baby zombie's oversized head comes from. Each ModelPart is rendered individually here,
    // bypassing AgeableListModel.renderToBuffer, so that split is reproduced by hand.
    private record HumanoidScale(float body, float head) {
        static final HumanoidScale ADULT = new HumanoidScale(1.0f, 1.0f);
        // Big-head baby (zombie/husk/piglin/drowned/zombie-villager): head 0.75, body 0.5.
        static final HumanoidScale BABY  = new HumanoidScale(0.5f, 0.75f);
        // Uniform baby (plain villager/wandering trader): everything at 0.5, no big head.
        static final HumanoidScale BABY_UNIFORM = new HumanoidScale(0.5f, 0.5f);
        float forPart(RagdollPart part) {
            return part == RagdollPart.HEAD ? head : body;
        }
    }

    private static void renderAnimalPart(PoseStack poseStack, VertexConsumer vc,
                                          ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                          float setPosX, float setPosY, float setPosZ,
                                          float defaultXRot, int light) {
        renderAnimalPart(poseStack, vc, part, transform, torso, setPosX, setPosY, setPosZ, defaultXRot, light, 1.0f);
    }

    private static void renderAnimalPart(PoseStack poseStack, VertexConsumer vc,
                                          ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                          float setPosX, float setPosY, float setPosZ,
                                          float defaultXRot, int light, float modelScale) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            if (modelScale != 1.0f) {
                poseStack.scale(modelScale, modelScale, modelScale);
            }

            part.setPos(setPosX, setPosY, setPosZ);
            part.xRot = defaultXRot;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
        } finally {
            poseStack.popPose();
        }
    }

    // Llama babies are the one vanilla animal here with different X/Y/Z scales per model
    // group. Keep that anisotropy instead of collapsing it to an inaccurate uniform scale.
    private static void renderAnimalPartScaledXYZ(PoseStack poseStack, VertexConsumer vc,
            ModelPart part, RagdollTransform transform, RagdollTransform torso,
            float setPosX, float setPosY, float setPosZ, float defaultXRot, int light,
            float scaleX, float scaleY, float scaleZ) {
        if (transform == null) return;
        poseStack.pushPose();
        try {
            poseStack.translate(transform.position.x-torso.position.x,
                    transform.position.y-torso.position.y, transform.position.z-torso.position.z);
            tempQuat.set(transform.rotation.x,transform.rotation.y,transform.rotation.z,transform.rotation.w);
            tempQuat.rotateZ((float)Math.PI);
            poseStack.mulPose(tempQuat);
            poseStack.scale(scaleX,scaleY,scaleZ);
            part.setPos(setPosX,setPosY,setPosZ);
            part.xRot=defaultXRot; part.yRot=0; part.zRot=0;
            part.render(poseStack,vc,light,OverlayTexture.NO_OVERLAY);
        } finally { poseStack.popPose(); }
    }

    private static void renderAnimalPartRotated(PoseStack poseStack, VertexConsumer vc,
            ModelPart part, RagdollTransform transform, RagdollTransform torso,
            float x,float y,float z,float xRot,float yRot,float zRot,int light,
            float sx,float sy,float sz) {
        if(transform==null)return; poseStack.pushPose();
        try{
            poseStack.translate(transform.position.x-torso.position.x,transform.position.y-torso.position.y,transform.position.z-torso.position.z);
            tempQuat.set(transform.rotation.x,transform.rotation.y,transform.rotation.z,transform.rotation.w).rotateZ((float)Math.PI);
            poseStack.mulPose(tempQuat); poseStack.scale(sx,sy,sz);
            part.setPos(x,y,z);part.xRot=xRot;part.yRot=yRot;part.zRot=zRot;
            part.render(poseStack,vc,light,OverlayTexture.NO_OVERLAY);
        }finally{poseStack.popPose();}
    }

    // renderAnimalPart with an RGB multiplier, used by the sheep wool overlay so every dye
    // colour works off one texture. ModelPart's 8-arg render pushes the r,g,b,a floats to the
    // shader as the per-vertex colour, exactly as vanilla SheepFurLayer does.
    private static void renderAnimalPartTinted(PoseStack poseStack, VertexConsumer vc,
                                                ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                                float setPosX, float setPosY, float setPosZ,
                                                float defaultXRot, int light,
                                                float r, float g, float b) {
        renderAnimalPartTinted(poseStack, vc, part, transform, torso,
                setPosX, setPosY, setPosZ, defaultXRot, light, r, g, b, 1.0f);
    }

    private static void renderAnimalPartTinted(PoseStack poseStack, VertexConsumer vc,
                                                ModelPart part, RagdollTransform transform, RagdollTransform torso,
                                                float setPosX, float setPosY, float setPosZ,
                                                float defaultXRot, int light,
                                                float r, float g, float b, float modelScale) {
        if (transform == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    transform.position.x - torso.position.x,
                    transform.position.y - torso.position.y,
                    transform.position.z - torso.position.z
            );

            tempQuat.set(transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w);
            tempQuat.rotateZ((float) Math.PI);
            poseStack.mulPose(tempQuat);
            if (modelScale != 1.0f) {
                poseStack.scale(modelScale, modelScale, modelScale);
            }

            part.setPos(setPosX, setPosY, setPosZ);
            part.xRot = defaultXRot;
            part.yRot = 0;
            part.zRot = 0;

            part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY, r, g, b, 1f);
        } finally {
            poseStack.popPose();
        }
    }

    // ============================
    // Helpers
    // ============================

    private static void resetPart(ModelPart part) {
        part.setPos(0, 0, 0);
        part.xRot = 0;
        part.yRot = 0;
        part.zRot = 0;
    }

    private static void makeAllChildrenVisible(ModelPart part) {
        part.visible = true;
        part.getAllParts().forEach(p -> p.visible = true);
    }

    // The mob's skin, with Visual Health's damage composited in when it's installed and the mob
    // died hurt. VisualHealthCompat keys its own cache off the resolved base texture, so the
    // ragdoll's cached texture stays the clean one and ETF/EMF variants still work.
    private static ResourceLocation getMobTexture(ClientRagdoll ragdoll) {
        return VisualHealthCompat.texture(ragdoll.getOriginalEntityId(), getBaseMobTexture(ragdoll));
    }

    private static ResourceLocation getBaseMobTexture(ClientRagdoll ragdoll) {
        ResourceLocation cached = ragdoll.getCachedTexture();
        if (cached != null) return cached;

        ResourceLocation fromCache = ClientMobTextureCache.getTextureForDeadMob(ragdoll.getOriginalEntityId());
        if (fromCache != null) {
            ragdoll.setCachedTexture(fromCache);
            return fromCache;
        }

        // Falling through here means the live mob's texture was never captured, and a modded mob
        // is about to be drawn with a guessed vanilla texture — which reads as "no texture at all"
        // whenever its UVs are its own. Worth saying out loud: nothing else reports it.
        warnOnce(UNTEXTURED_WARNED, ragdoll.getMobType(),
                "No captured texture for {} - falling back to a vanilla guess. Its ragdoll will "
                        + "look untextured if the mob does not use vanilla UVs.");

        ResourceLocation fallback = getFallbackTexture(ragdoll.getMobType());
        ragdoll.setCachedTexture(fallback);
        return fallback;
    }

    private static ResourceLocation getFallbackTexture(String mobType) {
        if (mobType.contains("zombie_horse")) return new ResourceLocation("minecraft", "textures/entity/horse/horse_zombie.png");
        if (mobType.contains("skeleton_horse")) return new ResourceLocation("minecraft", "textures/entity/horse/horse_skeleton.png");
        if (isExactMobPath(mobType, "donkey")) return new ResourceLocation("minecraft", "textures/entity/horse/donkey.png");
        if (isExactMobPath(mobType, "mule")) return new ResourceLocation("minecraft", "textures/entity/horse/mule.png");
        if (isExactMobPath(mobType, "horse")) return new ResourceLocation("minecraft", "textures/entity/horse/horse_brown.png");
        if (mobType.contains("witch")) return new ResourceLocation("minecraft", "textures/entity/witch.png");
        if (isExactMobPath(mobType, "wolf")) return new ResourceLocation("minecraft", "textures/entity/wolf/wolf.png");
        if (isExactMobPath(mobType, "fox")) return new ResourceLocation("minecraft", "textures/entity/fox/fox.png");
        if (isExactMobPath(mobType, "panda")) return new ResourceLocation("minecraft", "textures/entity/panda/panda.png");
        if (isExactMobPath(mobType, "iron_golem")) return new ResourceLocation("minecraft", "textures/entity/iron_golem/iron_golem.png");
        if (isExactMobPath(mobType, "goat")) return new ResourceLocation("minecraft", "textures/entity/goat/goat.png");
        if (isExactMobPath(mobType, "polar_bear")) return new ResourceLocation("minecraft", "textures/entity/bear/polarbear.png");
        if (isExactMobPath(mobType, "turtle")) return new ResourceLocation("minecraft", "textures/entity/turtle/big_sea_turtle.png");
        if (isExactMobPath(mobType, "enderman")) return new ResourceLocation("minecraft", "textures/entity/enderman/enderman.png");
        if (isExactMobPath(mobType, "camel")) return new ResourceLocation("minecraft", "textures/entity/camel/camel.png");
        if (isExactMobPath(mobType, "llama") || isExactMobPath(mobType, "trader_llama")) return new ResourceLocation("minecraft", "textures/entity/llama/creamy.png");
        if (isExactMobPath(mobType, "rabbit")) return new ResourceLocation("minecraft", "textures/entity/rabbit/brown.png");
        if (isExactMobPath(mobType, "frog")) return new ResourceLocation("minecraft", "textures/entity/frog/temperate_frog.png");
        if (isExactMobPath(mobType, "zoglin")) return new ResourceLocation("minecraft", "textures/entity/hoglin/zoglin.png");
        if (isExactMobPath(mobType, "hoglin")) return new ResourceLocation("minecraft", "textures/entity/hoglin/hoglin.png");
        if (isExactMobPath(mobType, "sniffer")) return new ResourceLocation("minecraft", "textures/entity/sniffer/sniffer.png");
        if (isExactMobPath(mobType, "ravager")) return new ResourceLocation("minecraft", "textures/entity/illager/ravager.png");
        if (isExactMobPath(mobType, "phantom")) return new ResourceLocation("minecraft", "textures/entity/phantom.png");
        if (isExactMobPath(mobType, "parrot")) return new ResourceLocation("minecraft", "textures/entity/parrot/parrot_red_blue.png");
        if (isExactMobPath(mobType, "magma_cube")) return new ResourceLocation("minecraft", "textures/entity/slime/magmacube.png");
        if (isExactMobPath(mobType, "slime")) return new ResourceLocation("minecraft", "textures/entity/slime/slime.png");
        if (isExactMobPath(mobType, "silverfish")) return new ResourceLocation("minecraft", "textures/entity/silverfish.png");
        if (isExactMobPath(mobType, "endermite")) return new ResourceLocation("minecraft", "textures/entity/endermite.png");
        if (isExactMobPath(mobType, "allay")) return new ResourceLocation("minecraft", "textures/entity/allay/allay.png");
        if (isExactMobPath(mobType, "strider")) return new ResourceLocation("minecraft", "textures/entity/strider/strider.png");
        if (isExactMobPath(mobType, "snow_golem")) return new ResourceLocation("minecraft", "textures/entity/snow_golem.png");
        if (isExactMobPath(mobType, "blaze")) return new ResourceLocation("minecraft", "textures/entity/blaze.png");
        if (isExactMobPath(mobType, "cave_spider")) return new ResourceLocation("minecraft", "textures/entity/spider/cave_spider.png");
        if (isExactMobPath(mobType, "spider")) return new ResourceLocation("minecraft", "textures/entity/spider/spider.png");
        if (isExactMobPath(mobType, "shulker")) return new ResourceLocation("minecraft", "textures/entity/shulker/shulker.png");
        if (isExactMobPath(mobType, "ghast")) return new ResourceLocation("minecraft", "textures/entity/ghast/ghast.png");
        if (isExactMobPath(mobType, "vex")) return new ResourceLocation("minecraft", "textures/entity/illager/vex.png");
        if (isExactMobPath(mobType, "warden")) return new ResourceLocation("minecraft", "textures/entity/warden/warden.png");
        if (mobType.contains("zombie") && !mobType.contains("piglin")) {
            if (mobType.contains("husk")) return new ResourceLocation("minecraft", "textures/entity/zombie/husk.png");
            if (mobType.contains("drowned")) return new ResourceLocation("minecraft", "textures/entity/zombie/drowned.png");
            if (mobType.contains("villager")) return new ResourceLocation("minecraft", "textures/entity/zombie_villager/zombie_villager.png");
            return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
        }
        if (mobType.contains("skeleton")) {
            if (mobType.contains("wither")) return new ResourceLocation("minecraft", "textures/entity/skeleton/wither_skeleton.png");
            if (mobType.contains("stray")) return new ResourceLocation("minecraft", "textures/entity/skeleton/stray.png");
            return new ResourceLocation("minecraft", "textures/entity/skeleton/skeleton.png");
        }
        if (mobType.contains("piglin")) {
            if (mobType.contains("brute")) return new ResourceLocation("minecraft", "textures/entity/piglin/piglin_brute.png");
            if (mobType.contains("zombified")) return new ResourceLocation("minecraft", "textures/entity/piglin/zombified_piglin.png");
            return new ResourceLocation("minecraft", "textures/entity/piglin/piglin.png");
        }
        if (mobType.contains("pillager")) return new ResourceLocation("minecraft", "textures/entity/illager/pillager.png");
        if (mobType.contains("vindicator")) return new ResourceLocation("minecraft", "textures/entity/illager/vindicator.png");
        if (mobType.contains("evoker")) return new ResourceLocation("minecraft", "textures/entity/illager/evoker.png");
        if (mobType.contains("illusioner")) return new ResourceLocation("minecraft", "textures/entity/illager/illusioner.png");
        if (mobType.contains("villager")) return new ResourceLocation("minecraft", "textures/entity/villager/villager.png");
        if (mobType.contains("wandering_trader")) return new ResourceLocation("minecraft", "textures/entity/wandering_trader.png");
        if (mobType.contains("creeper")) return new ResourceLocation("minecraft", "textures/entity/creeper/creeper.png");
        if (mobType.contains("cow")) return new ResourceLocation("minecraft", "textures/entity/cow/cow.png");
        if (mobType.contains("mooshroom")) return new ResourceLocation("minecraft", "textures/entity/cow/mooshroom.png");
        if (mobType.contains("sheep")) return new ResourceLocation("minecraft", "textures/entity/sheep/sheep.png");
        if (mobType.contains("pig")) return new ResourceLocation("minecraft", "textures/entity/pig/pig.png");
        // Ocelot before cat: "ocelot" doesn't contain "cat", but keep the specific case first.
        if (mobType.contains("ocelot")) return new ResourceLocation("minecraft", "textures/entity/cat/ocelot.png");
        if (mobType.contains("cat")) return new ResourceLocation("minecraft", "textures/entity/cat/tabby.png");
        if (mobType.contains("chicken")) return new ResourceLocation("minecraft", "textures/entity/chicken.png");
        if (mobType.contains("bat")) return new ResourceLocation("minecraft", "textures/entity/bat.png");
        if (mobType.contains("bee")) return new ResourceLocation("minecraft", "textures/entity/bee/bee.png");
        return new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
    }

    // Resolve a slot's armor texture, with type naming the layer — "overlay" for the dyeable
    // leather pass, null for the base. Honours the Forge per-item override so modded armor with
    // a custom path works, then falls back to vanilla's
    // textures/models/armor/<material>_layer_<n>[_<type>].png.
    private static ResourceLocation getArmorTexture(ArmorItem item, EquipmentSlot slot, ItemStack stack,
                                                    net.minecraft.world.entity.Entity entity, String type) {
        try {
            String texturePath = item.getArmorTexture(stack, entity, slot, type);
            if (texturePath != null && !texturePath.isEmpty()) {
                try { return new ResourceLocation(texturePath); } catch (Exception ignored) {}
            }
            String materialName = item.getMaterial().getName();
            if (materialName.contains(":")) {
                materialName = materialName.substring(materialName.lastIndexOf(":") + 1);
            }
            materialName = switch (materialName.toLowerCase()) {
                case "leather" -> "leather";
                case "chainmail", "chain" -> "chainmail";
                case "iron" -> "iron";
                case "gold", "golden" -> "gold";
                case "diamond" -> "diamond";
                case "netherite" -> "netherite";
                default -> materialName;
            };
            String layer = (slot == EquipmentSlot.LEGS) ? "layer_2" : "layer_1";
            String suffix = (type == null || type.isEmpty()) ? "" : "_" + type;
            return new ResourceLocation("minecraft", "textures/models/armor/" + materialName + "_" + layer + suffix + ".png");
        } catch (Exception e) {
            return new ResourceLocation("minecraft", "textures/models/armor/leather_layer_1.png");
        }
    }

    // ============================
    // Overlay descriptor system — extra layers (sea-grass on drowned, saddle on pig,
    // wool on sheep, energy swirl on charged creepers, …) drawn on top of the base
    // model. Each MobOverlay subtype targets one body shape (humanoid / quadruped /
    // creeper) so the dispatcher can call the matching render path. Add a new overlay
    // by appending an entry inside overlaysFor; villager profession layers (task 5)
    // will plug in here as several stacked HumanoidOverlay descriptors.
    // ============================

    public sealed interface MobOverlay permits HumanoidOverlay, IllagerOverlay, QuadrupedOverlay, VillagerOverlay, CreeperSwirlOverlay {}

    // Full HumanoidModel render (drowned outer, stray clothing, zombie-villager profession).
    public record HumanoidOverlay(ResourceLocation texture, HumanoidModel<?> model) implements MobOverlay {}

    // Villager profession, type, and level layer, drawn on VillagerModel plus split arms so it
    // covers the whole body. Plain villagers only; zombie villagers use HumanoidOverlay.
    public record VillagerOverlay(ResourceLocation texture) implements MobOverlay {}

    // Illager-shaped overlay for villager profession layers. IllagerModel extends
    // HierarchicalModel rather than HumanoidModel, so it needs its own dispatch. limitToHeadBody
    // exists because vanilla villager textures are cut for VillagerModel's UV layout, where the
    // arms sit at a different texOffs than IllagerModel's split arms — applying them anyway
    // pulls hat and body texture onto the arm cubes, the farmer-hat-on-stick-arms bug. Pass true
    // for villager profession layers to overlay only head and body, where the UVs do match.
    public record IllagerOverlay(ResourceLocation texture, ModelPart root, boolean limitToHeadBody) implements MobOverlay {}

    // Per-part quadruped overlay (pig saddle, sheep wool); pass tint=1,1,1 for plain.
    public record QuadrupedOverlay(ResourceLocation texture, ModelPart root, float r, float g, float b, float bodyScale) implements MobOverlay {}

    // Charged-creeper energy swirl — special RenderType + half-RGB tint + UV scroll.
    public record CreeperSwirlOverlay(ResourceLocation texture, CreeperModel<?> model) implements MobOverlay {}

    // Resolve every overlay that renders on top of a ragdoll's base model, dispatched on model
    // type, mob type, and per-mob state flags. Order matters: later entries draw on top.
    private static java.util.List<MobOverlay> overlaysFor(ClientRagdoll ragdoll) {
        java.util.List<MobOverlay> result = new java.util.ArrayList<>(2);
        String mobType = ragdoll.getMobType();
        MobModelHelper.ModelType modelType = ragdoll.getModelType();

        if (modelType == MobModelHelper.ModelType.HUMANOID_DROWNED && drownedOuterModel != null) {
            // Always-on sea-grass overlay; no entity state needed.
            result.add(new HumanoidOverlay(DROWNED_OUTER_TEXTURE, drownedOuterModel));
        }
        if (modelType == MobModelHelper.ModelType.HUMANOID_SKELETON
                && mobType.contains("stray") && strayClothingModel != null) {
            result.add(new HumanoidOverlay(STRAY_OUTER_TEXTURE, strayClothingModel));
        }
        if (modelType == MobModelHelper.ModelType.CREEPER && ragdoll.isChargedCreeper()
                && creeperPoweredModel != null) {
            result.add(new CreeperSwirlOverlay(CREEPER_POWERED_TEXTURE, creeperPoweredModel));
        }
        if (modelType == MobModelHelper.ModelType.QUADRUPED) {
            if (mobType.contains("pig") && ragdoll.isSaddledPig() && pigSaddleRoot != null) {
                result.add(new QuadrupedOverlay(PIG_SADDLE_TEXTURE, pigSaddleRoot, 1f, 1f, 1f,
                        ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f));
            }
            if (mobType.contains("sheep") && !ragdoll.wasSheared() && sheepFurRoot != null) {
                net.minecraft.world.item.DyeColor color =
                        net.minecraft.world.item.DyeColor.byId(ragdoll.getDyeColorId() & 0xF);
                float[] rgb = color.getTextureDiffuseColors();
                result.add(new QuadrupedOverlay(SHEEP_FUR_TEXTURE, sheepFurRoot, rgb[0], rgb[1], rgb[2],
                        ragdoll.usesBabyBodyScale() ? 0.5f : 1.0f));
            }
        }
        // Villager / zombie villager profession layers — three stacked textures (biome
        // type → profession → profession level) drawn over the base humanoid model.
        // Empty profession key (level 0 too) means "vanilla nitwit / unknown" — skip.
        if (modelType == MobModelHelper.ModelType.ILLAGER
                && !ragdoll.getVillagerType().isEmpty()
                && !ragdoll.getVillagerProfession().isEmpty()) {
            // Resolve the namespace from the registry-key string. "minecraft:plains" splits
            // to namespace="minecraft", path="plains". Fall back to vanilla namespace if
            // the key was malformed.
            net.minecraft.resources.ResourceLocation typeKey;
            net.minecraft.resources.ResourceLocation profKey;
            try {
                typeKey = new net.minecraft.resources.ResourceLocation(ragdoll.getVillagerType());
                profKey = new net.minecraft.resources.ResourceLocation(ragdoll.getVillagerProfession());
            } catch (Exception e) {
                return result;
            }

            boolean isZombieVillager = mobType.contains("zombie_villager");
            String basePath = isZombieVillager ? "textures/entity/zombie_villager/" : "textures/entity/villager/";
            // Vanilla VillagerProfessionLayer skips the profession overlay entirely when
            // profession == NONE (there is no profession/none.png in the jar — rendering
            // it gives the purple-and-black missing-texture indicator). Level layer also
            // skipped for NONE and NITWIT.
            boolean isNoneProfession = profKey.getNamespace().equals("minecraft")
                    && profKey.getPath().equals("none");
            boolean isNitwit = profKey.getNamespace().equals("minecraft")
                    && profKey.getPath().equals("nitwit");

            // 1. Biome-type overlay — always rendered, even for "none" profession.
            ResourceLocation typeTex = new ResourceLocation(typeKey.getNamespace(),
                    basePath + "type/" + typeKey.getPath() + ".png");
            result.add(villagerLayer(typeTex, isZombieVillager));

            // 2. Profession overlay — skip for NONE (matches vanilla, avoids missing texture).
            if (!isNoneProfession) {
                ResourceLocation profTex = new ResourceLocation(profKey.getNamespace(),
                        basePath + "profession/" + profKey.getPath() + ".png");
                result.add(villagerLayer(profTex, isZombieVillager));

                // 3. Profession-level necklace — skip for NONE and NITWIT.
                int level = ragdoll.getVillagerLevel();
                if (!isNitwit && level >= 1 && level <= 5) {
                    String levelName = switch (level) {
                        case 1 -> "stone";
                        case 2 -> "iron";
                        case 3 -> "gold";
                        case 4 -> "emerald";
                        case 5 -> "diamond";
                        default -> null;
                    };
                    if (levelName != null) {
                        ResourceLocation levelTex = new ResourceLocation("minecraft",
                                "textures/entity/villager/profession_level/" + levelName + ".png");
                        result.add(villagerLayer(levelTex, isZombieVillager));
                    }
                }
            }
        }
        return result;
    }

    // Pick the overlay carrier for a villager layer: zombie villagers use the humanoid
    // ZombieVillagerModel, plain villagers VillagerModel plus split arms.
    private static MobOverlay villagerLayer(ResourceLocation texture, boolean zombieVillager) {
        return zombieVillager ? new HumanoidOverlay(texture, zombieVillagerModel) : new VillagerOverlay(texture);
    }

    // Dispatch one overlay to its render path. The transforms are the base model's; overlays
    // ride the same physics bodies.
    private static void renderOverlay(MobOverlay overlay, PoseStack poseStack, MultiBufferSource buffer, int light,
                                      RagdollTransform torso, RagdollTransform head,
                                      RagdollTransform larm, RagdollTransform rarm,
                                      RagdollTransform lleg, RagdollTransform rleg, HumanoidScale humanoidScale) {
        if (overlay instanceof HumanoidOverlay h) {
            VertexConsumer ovc = buffer.getBuffer(RenderType.entityCutoutNoCull(h.texture()));
            renderHumanoidMob(poseStack, ovc, light, torso, head, larm, rarm, lleg, rleg, h.model(), humanoidScale);
        } else if (overlay instanceof IllagerOverlay i) {
            renderIllagerOverlayParts(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, i, humanoidScale);
        } else if (overlay instanceof VillagerOverlay vil) {
            VertexConsumer ovc = buffer.getBuffer(RenderType.entityCutoutNoCull(vil.texture()));
            renderVillagerParts(poseStack, ovc, light, torso, head, larm, rarm, lleg, rleg, humanoidScale);
        } else if (overlay instanceof QuadrupedOverlay q) {
            renderQuadrupedOverlayParts(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, q);
        } else if (overlay instanceof CreeperSwirlOverlay c) {
            renderCreeperSwirlParts(poseStack, buffer, light, torso, head, larm, rarm, lleg, rleg, c);
        }
    }

    // Per-part illager-shaped overlay for villager profession layers: renderIllager's
    // part-by-part dispatch with a different texture per call.
    private static void renderIllagerOverlayParts(PoseStack poseStack, MultiBufferSource buffer, int light,
                                                  RagdollTransform torso, RagdollTransform head,
                                                  RagdollTransform larm, RagdollTransform rarm,
                                                  RagdollTransform lleg, RagdollTransform rleg,
                                                  IllagerOverlay overlay, HumanoidScale modelScale) {
        ModelPart root = overlay.root();
        ModelPart body = root.getChild("body");
        ModelPart headPart = root.getChild("head");
        ModelPart leftLeg = root.getChild("left_leg");
        ModelPart rightLeg = root.getChild("right_leg");
        ModelPart leftArm = root.getChild("left_arm");
        ModelPart rightArm = root.getChild("right_arm");

        // IllagerModel default is to render arms together as a single "arms" part — we
        // hide that on each render so only the per-side arms draw, matching the base
        // illager render path.
        try { root.getChild("arms").visible = false; } catch (Exception ignored) {}
        leftArm.visible = true;
        rightArm.visible = true;

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(overlay.texture()));
        renderHumanoidPartPhysics(poseStack, vc, body,     torso, torso, light, RagdollPart.TORSO, modelScale);
        renderHumanoidPartPhysics(poseStack, vc, headPart, head,  torso, light, RagdollPart.HEAD, modelScale);
        if (!overlay.limitToHeadBody()) {
            renderHumanoidPartPhysics(poseStack, vc, leftLeg,  lleg,  torso, light, RagdollPart.LEFT_LEG, modelScale);
            renderHumanoidPartPhysics(poseStack, vc, rightLeg, rleg,  torso, light, RagdollPart.RIGHT_LEG, modelScale);
            renderHumanoidPartPhysics(poseStack, vc, leftArm,  larm,  torso, light, RagdollPart.LEFT_ARM, modelScale);
            renderHumanoidPartPhysics(poseStack, vc, rightArm, rarm,  torso, light, RagdollPart.RIGHT_ARM, modelScale);
        }
    }

    // Per-part quadruped overlay rendering — same setPos derivation as the base model.
    private static void renderQuadrupedOverlayParts(PoseStack poseStack, MultiBufferSource buffer, int light,
                                                    RagdollTransform torso, RagdollTransform head,
                                                    RagdollTransform larm, RagdollTransform rarm,
                                                    RagdollTransform lleg, RagdollTransform rleg,
                                                    QuadrupedOverlay overlay) {
        ModelPart root = overlay.root();
        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");
        ModelPart rightFront = root.getChild("right_front_leg");

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(overlay.texture()));
        float halfPI = (float) (Math.PI / 2);
        float r = overlay.r(), g = overlay.g(), b = overlay.b();

        org.joml.Vector3f bodyOff      = setPosForPart(body, halfPI);
        org.joml.Vector3f headOff      = setPosForPart(headPart, 0);
        org.joml.Vector3f lHindOff     = setPosForPart(leftHind, 0);
        org.joml.Vector3f rHindOff     = setPosForPart(rightHind, 0);
        org.joml.Vector3f lFrontOff    = setPosForPart(leftFront, 0);
        org.joml.Vector3f rFrontOff    = setPosForPart(rightFront, 0);

        // The sheep wool model (SheepFurModel) doesn't sit flush on the physics-aligned body
        // it rides: the head wool is 1px too far forward and the leg wool 3px too low. Correct
        // only the wool — the pig saddle shares this path and must not move. Offsets are in
        // model-pixel space (setPos units): +Z is toward the back of the head (face is -Z) and
        // -Y is up (model +Y points down), and they scale with the part so baby sheep line up too.
        boolean isWool = overlay.texture().equals(SHEEP_FUR_TEXTURE);
        float woolHeadZ = isWool ? 1.0f  : 0.0f;
        float woolLegY  = isWool ? -3.0f : 0.0f;

        renderAnimalPartTinted(poseStack, vc, body,       torso, torso, bodyOff.x,    bodyOff.y,             bodyOff.z,               halfPI, light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, headPart,   head,  torso, headOff.x,    headOff.y,             headOff.z + woolHeadZ,   0,      light, r, g, b);
        renderAnimalPartTinted(poseStack, vc, leftHind,   lleg,  torso, lHindOff.x,   lHindOff.y + woolLegY, lHindOff.z,              0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, rightHind,  rleg,  torso, rHindOff.x,   rHindOff.y + woolLegY, rHindOff.z,              0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, leftFront,  larm,  torso, lFrontOff.x,  lFrontOff.y + woolLegY, lFrontOff.z,            0,      light, r, g, b, overlay.bodyScale());
        renderAnimalPartTinted(poseStack, vc, rightFront, rarm,  torso, rFrontOff.x,  rFrontOff.y + woolLegY, rFrontOff.z,            0,      light, r, g, b, overlay.bodyScale());
    }

    // Charged-creeper energy swirl. Vanilla EnergySwirlLayer draws the inflated creeper armor
    // model at half RGB with time-driven UV scrolling; corpses do not tick, so wall-clock time
    // stands in for the same continuous shimmer.
    private static void renderCreeperSwirlParts(PoseStack poseStack, MultiBufferSource buffer, int light,
                                                RagdollTransform torso, RagdollTransform head,
                                                RagdollTransform larm, RagdollTransform rarm,
                                                RagdollTransform lleg, RagdollTransform rleg,
                                                CreeperSwirlOverlay overlay) {
        ModelPart root = overlay.model().root();
        ModelPart body       = root.getChild("body");
        ModelPart headPart   = root.getChild("head");
        ModelPart leftHind   = root.getChild("left_hind_leg");
        ModelPart rightHind  = root.getChild("right_hind_leg");
        ModelPart leftFront  = root.getChild("left_front_leg");
        ModelPart rightFront = root.getChild("right_front_leg");

        float t = (System.currentTimeMillis() % 100_000L) / 1000.0f;
        float u = (t * 0.01f) % 1.0f;
        float v = (t * 0.01f) % 1.0f;
        VertexConsumer vc = buffer.getBuffer(RenderType.energySwirl(overlay.texture(), u, v));

        renderHumanoidPartPhysicsTinted(poseStack, vc, body,     torso, torso, light, RagdollPart.TORSO, 0.5f, 0.5f, 0.5f, 1f);
        renderHumanoidPartPhysicsTinted(poseStack, vc, headPart, head,  torso, light, RagdollPart.HEAD,  0.5f, 0.5f, 0.5f, 1f);
        renderAnimalPartTinted(poseStack, vc, leftHind,   lleg, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
        renderAnimalPartTinted(poseStack, vc, rightHind,  rleg, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
        renderAnimalPartTinted(poseStack, vc, leftFront,  larm, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
        renderAnimalPartTinted(poseStack, vc, rightFront, rarm, torso, 0, -3, 0, 0, light, 0.5f, 0.5f, 0.5f);
    }

    // Cache of ModelPart to cube bbox centre in part-local pixel coords. The ModelPart instances
    // live as long as the renderer and their cubes never change after bake, so entries stay
    // valid. WeakHashMap so a resource reload does not leak.
    private static final java.util.Map<ModelPart, org.joml.Vector3f> CUBE_CENTER_CACHE =
            new java.util.WeakHashMap<>();

    // Geometric centre of a part's own cubes, children excluded, in part-local pixel coords.
    // The renderer overrides each pivot via setPos, so this centre is what has to be inverted
    // and rotated to land the cube on its physics body. ModelPart.cubes is private, so it is
    // read reflectively and cached after the first read.
    private static org.joml.Vector3f cubeBoxCenter(ModelPart part) {
        org.joml.Vector3f cached = CUBE_CENTER_CACHE.get(part);
        if (cached != null) return cached;

        java.util.List<ModelPart.Cube> cubes;
        try {
            cubes = net.minecraftforge.fml.util.ObfuscationReflectionHelper.getPrivateValue(
                    ModelPart.class, part, "f_104212_");
        } catch (Exception e) {
            Ragdollified.LOGGER.warn("Could not access ModelPart cubes via reflection — falling back to zero offset", e);
            org.joml.Vector3f zero = new org.joml.Vector3f();
            CUBE_CENTER_CACHE.put(part, zero);
            return zero;
        }

        if (cubes == null || cubes.isEmpty()) {
            org.joml.Vector3f zero = new org.joml.Vector3f();
            CUBE_CENTER_CACHE.put(part, zero);
            return zero;
        }

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (ModelPart.Cube c : cubes) {
            minX = Math.min(minX, c.minX); maxX = Math.max(maxX, c.maxX);
            minY = Math.min(minY, c.minY); maxY = Math.max(maxY, c.maxY);
            minZ = Math.min(minZ, c.minZ); maxZ = Math.max(maxZ, c.maxZ);
        }
        org.joml.Vector3f center = new org.joml.Vector3f(
                (minX + maxX) * 0.5f,
                (minY + maxY) * 0.5f,
                (minZ + maxZ) * 0.5f);
        CUBE_CENTER_CACHE.put(part, center);
        return center;
    }

    private static org.joml.Vector3f setPosForPart(ModelPart part, float defaultXRot) {
        org.joml.Vector3f c = cubeBoxCenter(part);
        float cosA = (float) Math.cos(defaultXRot);
        float sinA = (float) Math.sin(defaultXRot);
        // Rotate (c.y, c.z) around X by defaultXRot: y' = c.y*cos - c.z*sin, z' = c.y*sin + c.z*cos.
        float ry = c.y * cosA - c.z * sinA;
        float rz = c.y * sinA + c.z * cosA;
        return new org.joml.Vector3f(-c.x, -ry, -rz);
    }
}
