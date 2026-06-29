package com.raiiiden.ragdollified.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class GeckoLibArmorHelper {
    private static boolean geckoLibChecked = false;
    private static boolean geckoLibAvailable = false;
    private static final Map<Class<?>, ArmorReflectionCache> cacheMap = new HashMap<>();
    // Cached proxy ArmorStand used as a LivingEntity stand-in for GeckoLib when the real entity isn't available
    private static ArmorStand proxyEntity = null;

    public static boolean isGeckoLibAvailable() {
        if (!geckoLibChecked) {
            try {
                Class.forName("software.bernie.geckolib.animatable.GeoItem");
                Class.forName("software.bernie.geckolib.renderer.GeoArmorRenderer");
                geckoLibAvailable = true;
                Ragdollified.LOGGER.info("GeckoLib detected - animated armor support enabled");
            } catch (ClassNotFoundException e) {
                geckoLibAvailable = false;
                Ragdollified.LOGGER.info("GeckoLib not found - only vanilla armor will be rendered");
            }
            geckoLibChecked = true;
        }
        return geckoLibAvailable;
    }

    public static boolean isGeckoLibArmor(Item item) {
        if (!isGeckoLibAvailable()) return false;
        try {
            Class<?> geoItemClass = Class.forName("software.bernie.geckolib.animatable.GeoItem");
            return geoItemClass.isInstance(item);
        } catch (Exception e) {
            return false;
        }
    }

    public static void renderGeckoLibArmor(ItemStack stack, EquipmentSlot slot, net.minecraft.world.entity.Entity entity,
                                           PoseStack poseStack, MultiBufferSource buffer, int light, int overlay,
                                           HumanoidModel<?> baseModel) {
        if (!isGeckoLibAvailable()) return;

        // GeckoLib requires a LivingEntity — use a proxy ArmorStand if the real entity isn't one
        LivingEntity livingEntity;
        if (entity instanceof LivingEntity le) {
            livingEntity = le;
        } else {
            livingEntity = getOrCreateProxy();
            if (livingEntity == null) return;
        }

        try {
            Item item = stack.getItem();
            ArmorReflectionCache cache = getOrCreateCache(item.getClass());
            if (cache == null || !cache.isValid()) return;

            Object renderer = cache.getRenderer(item, livingEntity, stack, slot);
            if (renderer == null) return;

            cache.prepareRenderer(renderer, livingEntity, stack, slot, baseModel);
            cache.renderArmor(renderer, poseStack, buffer, light, overlay);
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Failed to render GeckoLib armor: " + e.getMessage(), e);
        }
    }

    /**
     * Shared invisible ArmorStand used as a LivingEntity stand-in when no real entity is
     * available. Exposed so the vanilla/modded armor path can pass a non-null entity to
     * Forge's getHumanoidArmorModel hook (many mods assume one). May be null very early
     * before the level loads.
     */
    public static LivingEntity getProxyEntity() {
        return getOrCreateProxy();
    }

    private static ArmorStand getOrCreateProxy() {
        if (proxyEntity != null) return proxyEntity;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        proxyEntity = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
        proxyEntity.setInvisible(true);
        return proxyEntity;
    }

    public static void onWorldUnload() {
        proxyEntity = null;
    }

    private static ArmorReflectionCache getOrCreateCache(Class<?> itemClass) {
        return cacheMap.computeIfAbsent(itemClass, k -> {
            try {
                return new ArmorReflectionCache(k);
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Failed to create reflection cache for " + k.getSimpleName(), e);
                return null;
            }
        });
    }

    public static void clearCache() {
        cacheMap.clear();
        Ragdollified.LOGGER.debug("GeckoLib armor cache cleared");
    }

    private static class ArmorReflectionCache {
        private final Class<?> itemClass;
        private final Map<Item, Object> rendererInstances = new HashMap<>();
        private Method initializeClient;
        private Method getHumanoidArmorModel;
        private Method prepForRender;
        private boolean valid = false;

        public ArmorReflectionCache(Class<?> itemClass) throws Exception {
            this.itemClass = itemClass;
            initializeReflection();
        }

        private void initializeReflection() throws Exception {
            try {
                // Original methods for getting the renderer
                this.initializeClient = itemClass.getMethod("initializeClient", java.util.function.Consumer.class);

                Class<?> clientExtensions = Class.forName("net.minecraftforge.client.extensions.common.IClientItemExtensions");
                this.getHumanoidArmorModel = clientExtensions.getMethod("getHumanoidArmorModel",
                        LivingEntity.class, ItemStack.class, EquipmentSlot.class, HumanoidModel.class);

                Class<?> geoArmorRenderer = Class.forName("software.bernie.geckolib.renderer.GeoArmorRenderer");

                // Method to prepare the renderer with base model transforms
                this.prepForRender = geoArmorRenderer.getMethod("prepForRender",
                        net.minecraft.world.entity.Entity.class, ItemStack.class, EquipmentSlot.class, HumanoidModel.class);

                this.valid = true;
                Ragdollified.LOGGER.debug("Successfully initialized GeckoLib reflection for " + itemClass.getSimpleName());
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Failed to initialize reflection for " + itemClass.getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }

        public boolean isValid() {
            return valid;
        }

        public Object getRenderer(Item item, net.minecraft.world.entity.Entity entity, ItemStack stack, EquipmentSlot slot) {
            Object cached = rendererInstances.get(item);
            if (cached != null) return cached;

            try {
                final Object[] holder = new Object[1];
                initializeClient.invoke(item, (java.util.function.Consumer<Object>) extensions -> {
                    try {
                        Object renderer = getHumanoidArmorModel.invoke(extensions,
                                entity instanceof LivingEntity ? (LivingEntity) entity : null, stack, slot, null);
                        holder[0] = renderer;
                    } catch (Exception e) {
                        Ragdollified.LOGGER.error("Error getting armor renderer from extensions", e);
                    }
                });

                if (holder[0] != null) {
                    rendererInstances.put(item, holder[0]);
                    Ragdollified.LOGGER.debug("Created GeckoLib renderer for " + item.getDescriptionId());
                }
                return holder[0];
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Failed to get GeckoLib renderer", e);
                return null;
            }
        }

        public void prepareRenderer(Object renderer, net.minecraft.world.entity.Entity entity, ItemStack stack,
                                    EquipmentSlot slot, HumanoidModel<?> baseModel) {
            try {
                prepForRender.invoke(renderer, entity, stack, slot, baseModel);
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Error preparing GeckoLib renderer", e);
            }
        }

        public void renderArmor(Object renderer, PoseStack poseStack, MultiBufferSource buffer,
                                int light, int overlay) {
            if (!(renderer instanceof HumanoidModel<?> armorModel)) return;

            poseStack.pushPose();
            try {
                armorModel.renderToBuffer(
                        poseStack,           // PoseStack
                        null,                // VertexConsumer - GeckoLib gets this internally
                        light,               // packedLight
                        overlay,             // packedOverlay
                        1.0f,                // red
                        1.0f,                // green
                        1.0f,                // blue
                        1.0f                 // alpha
                );
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Error rendering GeckoLib armor: " + e.getMessage());
            } finally {
                poseStack.popPose();
            }
        }
    }
}
