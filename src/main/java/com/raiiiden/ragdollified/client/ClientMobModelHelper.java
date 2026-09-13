package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class ClientMobModelHelper {
    private static final Set<String> HUMANOID_PART_NAMES = Set.of(
            "head", "body", "rightarm", "leftarm", "rightleg", "leftleg"
    );

    private ClientMobModelHelper() {
    }

    public static MobModelHelper.ModelType getActualModelType(LivingEntity entity) {
        try {
            Minecraft mc = Minecraft.getInstance();
            EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);

            if (!(renderer instanceof LivingEntityRenderer)) {
                return MobModelHelper.ModelType.UNSUPPORTED;
            }

            EntityModel<?> model = ((LivingEntityRenderer<?, ?>) renderer).getModel();

            if (model instanceof net.minecraft.client.model.WitchModel) return MobModelHelper.ModelType.WITCH;
            if (model instanceof net.minecraft.client.model.HorseModel) return MobModelHelper.ModelType.EQUINE;
            if (model instanceof net.minecraft.client.model.WolfModel) return MobModelHelper.ModelType.WOLF;
            if (model instanceof net.minecraft.client.model.FoxModel) return MobModelHelper.ModelType.FOX;
            if (model instanceof net.minecraft.client.model.PandaModel) return MobModelHelper.ModelType.PANDA;
            if (model instanceof net.minecraft.client.model.IronGolemModel) return MobModelHelper.ModelType.IRON_GOLEM;
            if (model instanceof net.minecraft.client.model.GoatModel) return MobModelHelper.ModelType.GOAT;
            if (model instanceof net.minecraft.client.model.PolarBearModel) return MobModelHelper.ModelType.POLAR_BEAR;
            if (model instanceof net.minecraft.client.model.TurtleModel) return MobModelHelper.ModelType.TURTLE;
            if (model instanceof net.minecraft.client.model.EndermanModel) return MobModelHelper.ModelType.ENDERMAN;
            if (model instanceof net.minecraft.client.model.CamelModel) return MobModelHelper.ModelType.CAMEL;
            if (model instanceof net.minecraft.client.model.LlamaModel) return MobModelHelper.ModelType.LLAMA;
            if (model instanceof net.minecraft.client.model.RabbitModel) return MobModelHelper.ModelType.RABBIT;
            if (model instanceof net.minecraft.client.model.FrogModel) return MobModelHelper.ModelType.FROG;
            if (model instanceof net.minecraft.client.model.HoglinModel) return MobModelHelper.ModelType.HOGLIN;
            if (model instanceof net.minecraft.client.model.SnifferModel) return MobModelHelper.ModelType.SNIFFER;
            if (model instanceof net.minecraft.client.model.RavagerModel) return MobModelHelper.ModelType.RAVAGER;
            if (model instanceof net.minecraft.client.model.PhantomModel) return MobModelHelper.ModelType.PHANTOM;
            if (model instanceof net.minecraft.client.model.ParrotModel) return MobModelHelper.ModelType.PARROT;
            if (model instanceof net.minecraft.client.model.LavaSlimeModel) return MobModelHelper.ModelType.MAGMA_CUBE;
            if (model instanceof net.minecraft.client.model.SlimeModel) return MobModelHelper.ModelType.SLIME;
            if (model instanceof net.minecraft.client.model.SilverfishModel) return MobModelHelper.ModelType.SILVERFISH;
            if (model instanceof net.minecraft.client.model.EndermiteModel) return MobModelHelper.ModelType.ENDERMITE;
            if (model instanceof net.minecraft.client.model.AllayModel) return MobModelHelper.ModelType.ALLAY;
            if (model instanceof net.minecraft.client.model.StriderModel) return MobModelHelper.ModelType.STRIDER;
            if (model instanceof net.minecraft.client.model.SnowGolemModel) return MobModelHelper.ModelType.SNOW_GOLEM;
            if (model instanceof net.minecraft.client.model.BlazeModel) return MobModelHelper.ModelType.BLAZE;
            if (model instanceof net.minecraft.client.model.SpiderModel) return MobModelHelper.ModelType.SPIDER;
            if (model instanceof net.minecraft.client.model.ShulkerModel) return MobModelHelper.ModelType.SHULKER;
            if (model instanceof net.minecraft.client.model.GhastModel) return MobModelHelper.ModelType.GHAST;
            if (model instanceof net.minecraft.client.model.VexModel) return MobModelHelper.ModelType.VEX;
            if (model instanceof net.minecraft.client.model.WardenModel) return MobModelHelper.ModelType.WARDEN;
            if (model instanceof net.minecraft.client.model.GuardianModel) return MobModelHelper.ModelType.GUARDIAN;
            if (model instanceof net.minecraft.client.model.SquidModel) return MobModelHelper.ModelType.SQUID;
            if (model instanceof net.minecraft.client.model.DolphinModel) return MobModelHelper.ModelType.DOLPHIN;
            if (model instanceof net.minecraft.client.model.AxolotlModel) return MobModelHelper.ModelType.AXOLOTL;
            // Every small-fish model reduces to the same body+tail rig; the BodyProfile picks the
            // dimensions and the root to draw.
            if (model instanceof net.minecraft.client.model.CodModel
                    || model instanceof net.minecraft.client.model.SalmonModel
                    || model instanceof net.minecraft.client.model.TropicalFishModelA
                    || model instanceof net.minecraft.client.model.TropicalFishModelB
                    || model instanceof net.minecraft.client.model.PufferfishBigModel
                    || model instanceof net.minecraft.client.model.PufferfishMidModel
                    || model instanceof net.minecraft.client.model.PufferfishSmallModel
                    || model instanceof net.minecraft.client.model.TadpoleModel) return MobModelHelper.ModelType.FISH;
            if (model instanceof net.minecraft.client.model.WitherBossModel) return MobModelHelper.ModelType.WITHER;
            if (model instanceof IllagerModel) return MobModelHelper.ModelType.ILLAGER;
            // Villagers have one combined 'arms' part, so claim VillagerModel as ILLAGER here;
            // a GENERIC rig would mispose the shared live model. Witches are claimed earlier.
            if (model instanceof net.minecraft.client.model.VillagerModel) return MobModelHelper.ModelType.ILLAGER;
            // ZombieVillagerModel is a HumanoidModel, so it has to be claimed before the generic
            // humanoid fallback below or it would lose the zombie-villager UVs its texture needs.
            if (model instanceof net.minecraft.client.model.ZombieVillagerModel) return MobModelHelper.ModelType.ILLAGER;
            if (model instanceof net.minecraft.client.model.CreeperModel) return MobModelHelper.ModelType.CREEPER;
            if (model instanceof net.minecraft.client.model.SkeletonModel) return MobModelHelper.ModelType.HUMANOID_SKELETON;
            if (model instanceof net.minecraft.client.model.DrownedModel) return MobModelHelper.ModelType.HUMANOID_DROWNED;
            if (model instanceof ChickenModel) return MobModelHelper.ModelType.CHICKEN;
            if (model instanceof net.minecraft.client.model.BatModel) return MobModelHelper.ModelType.BAT;
            if (model instanceof net.minecraft.client.model.BeeModel) return MobModelHelper.ModelType.BEE;
            // OcelotModel (cats + ocelots) is not a QuadrupedModel subclass, so it needs an
            // explicit check before the generic fallbacks.
            if (model instanceof net.minecraft.client.model.OcelotModel) return MobModelHelper.ModelType.QUADRUPED;
            if (model instanceof QuadrupedModel) return MobModelHelper.ModelType.QUADRUPED;
            if (model instanceof HumanoidModel) return MobModelHelper.ModelType.HUMANOID_STANDARD;

            if (model instanceof net.minecraft.client.model.HierarchicalModel && hasHumanoidParts(model)) {
                return MobModelHelper.ModelType.HUMANOID_STANDARD;
            }

            // Nothing matched: measure a GENERIC rig off the model. Last, so authored rigs keep their detail.
            // Opt-in, checked first so a disabled rig is never measured at all.
            if (RagdollifiedConfig.get(RagdollifiedConfig.ENABLE_GENERIC_MOB_RAGDOLLS)
                    && GenericRigExtractor.canExtract(entity)) {
                return MobModelHelper.ModelType.GENERIC;
            }

            return MobModelHelper.ModelType.UNSUPPORTED;
        } catch (Exception e) {
            return MobModelHelper.ModelType.UNSUPPORTED;
        }
    }

    private static boolean hasHumanoidParts(EntityModel<?> model) {
        Set<String> found = new HashSet<>();

        // Mesh child names first: they survive reobfuscation, so on a production jar this is the only
        // branch that can match a vanilla-mapped model, where the field and method scans see SRG names.
        if (model instanceof net.minecraft.client.model.HierarchicalModel<?> hierarchical) {
            ModelPartTree.forEachNamed(hierarchical.root(), (name, part) -> {
                String n = ModelPartTree.normalize(name);
                if (HUMANOID_PART_NAMES.contains(n)) found.add(n);
            });
            if (found.containsAll(HUMANOID_PART_NAMES)) return true;
        }

        // Java field and method names. Correct only for classes that ship unobfuscated, which
        // in production means third-party models, exactly what still reaches this fallback.
        Class<?> cls = model.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() != ModelPart.class) continue;
                String n = f.getName().toLowerCase().replace("_", "");
                if (HUMANOID_PART_NAMES.contains(n)) found.add(n);
            }
            cls = cls.getSuperclass();
        }

        if (found.containsAll(HUMANOID_PART_NAMES)) return true;

        for (Method m : model.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != ModelPart.class) continue;
            String n = m.getName().toLowerCase();
            if (n.startsWith("get")) n = n.substring(3);
            n = n.replace("_", "");
            if (HUMANOID_PART_NAMES.contains(n)) found.add(n);
        }

        return found.containsAll(HUMANOID_PART_NAMES);
    }

    public static boolean isHumanoidLike(LivingEntity entity) {
        return MobModelHelper.isHumanoidModelType(getActualModelType(entity));
    }
}
