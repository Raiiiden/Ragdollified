package com.raiiiden.ragdollified.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class GeckoLibArmorHelper {
    private static boolean geckoLibChecked = false;
    private static boolean geckoLibAvailable = false;
    private static final Map<Class<?>, ArmorReflectionCache> cacheMap = new HashMap<>();

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

        try {
            Item item = stack.getItem();
            ArmorReflectionCache cache = getOrCreateCache(item.getClass());
            if (cache == null || !cache.isValid()) return;

            Object renderer = cache.getRenderer(item, entity, stack, slot);
            if (renderer == null) return;

            cache.prepareRenderer(renderer, entity, stack, slot, baseModel);
            cache.render(renderer, poseStack, buffer, light, overlay);
        } catch (Exception e) {
            Ragdollified.LOGGER.error("Failed to render GeckoLib armor: " + e.getMessage(), e);
        }
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
        private Method renderToBuffer;
        private boolean valid = false;

        public ArmorReflectionCache(Class<?> itemClass) throws Exception {
            this.itemClass = itemClass;
            initializeReflection();
        }

        private void initializeReflection() throws Exception {
            try {
                this.initializeClient = itemClass.getMethod("initializeClient", java.util.function.Consumer.class);

                Class<?> clientExtensions = Class.forName("net.minecraftforge.client.extensions.common.IClientItemExtensions");
                this.getHumanoidArmorModel = clientExtensions.getMethod("getHumanoidArmorModel",
                        LivingEntity.class, ItemStack.class, EquipmentSlot.class, HumanoidModel.class);

                Class<?> geoArmorRenderer = Class.forName("software.bernie.geckolib.renderer.GeoArmorRenderer");
                this.prepForRender = geoArmorRenderer.getMethod("prepForRender",
                        net.minecraft.world.entity.Entity.class, ItemStack.class, EquipmentSlot.class, HumanoidModel.class);

                this.renderToBuffer = HumanoidModel.class.getMethod("renderToBuffer",
                        PoseStack.class, VertexConsumer.class, int.class, int.class,
                        float.class, float.class, float.class, float.class);

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

        public void render(Object renderer, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
            try {
                renderToBuffer.invoke(renderer, poseStack, (VertexConsumer) null, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            } catch (Exception e) {
                Ragdollified.LOGGER.error("Error rendering GeckoLib armor: " + e.getMessage(), e);
            }
        }
    }
}