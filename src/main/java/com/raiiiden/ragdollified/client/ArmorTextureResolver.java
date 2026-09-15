package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Finds the texture a piece of armor draws with, the same way vanilla's armor layer does, and says
// whether any loaded resource pack actually has it. A texture that is named but absent draws as the
// purple-and-black missing texture, so the renderer checks here first and swaps in the fallback.
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ArmorTextureResolver {

    // Whether each texture exists, asked once per texture: the lookup walks every resource pack, and
    // armor is drawn every frame. Cleared on resource reload, since packs can add or remove textures.
    private static final Map<ResourceLocation, Boolean> EXISTS = new ConcurrentHashMap<>();

    private ArmorTextureResolver() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> EXISTS.clear());
    }

    // The texture HumanoidArmorLayer#getArmorResource would use: the material name's namespace is kept,
    // so "magistuarmory:armet" looks in that mod's assets, then the item may override it through Forge.
    // type is "overlay" for the dyeable second pass and null for the base. Null if the item errors.
    public static ResourceLocation resolve(ArmorItem item, EquipmentSlot slot, ItemStack stack,
                                           Entity wearer, String type) {
        try {
            String path = defaultPath(item.getMaterial().getName(), slot, type);
            return ResourceLocation.tryParse(ForgeHooksClient.getArmorTexture(wearer, stack, path, slot, type));
        } catch (Exception e) {
            Ragdollified.LOGGER.debug("Armor texture lookup failed for {}: {}", stack, e.getMessage());
            return null;
        }
    }

    public static boolean exists(ResourceLocation texture) {
        return EXISTS.computeIfAbsent(texture,
                t -> Minecraft.getInstance().getResourceManager().getResource(t).isPresent());
    }

    // The configured stand-in for armor whose own texture is missing, or null to draw no armor at all.
    // A fallback that is itself missing also draws nothing rather than the purple texture.
    public static ResourceLocation fallback(EquipmentSlot slot) {
        String material = RagdollifiedConfig.MISSING_ARMOR_FALLBACK.get().trim().toLowerCase(Locale.ROOT);
        if (material.isEmpty()) return null;
        ResourceLocation texture = ResourceLocation.tryParse(defaultPath(material, slot, null));
        return texture != null && exists(texture) ? texture : null;
    }

    private static String defaultPath(String material, EquipmentSlot slot, String type) {
        String domain = "minecraft";
        int colon = material.indexOf(':');
        if (colon != -1) {
            domain = material.substring(0, colon);
            material = material.substring(colon + 1);
        }
        return String.format(Locale.ROOT, "%s:textures/models/armor/%s_layer_%d%s.png", domain, material,
                slot == EquipmentSlot.LEGS ? 2 : 1, type == null ? "" : "_" + type);
    }
}
