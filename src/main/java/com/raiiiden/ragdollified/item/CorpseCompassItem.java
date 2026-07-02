package com.raiiiden.ragdollified.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * A compass bound to a specific corpse. The target (corpse id, position, dimension, owner name)
 * is baked into the stack NBT at creation time, so the needle can point and the locator screen
 * can show coordinates without any per-frame networking — it works even when the corpse's chunk
 * is unloaded, which is the whole point of the item.
 *
 * <p>Right-clicking opens the locator screen (wired client-side); see the client item-property
 * registration for the spinning needle behavior.
 */
public class CorpseCompassItem extends Item {

    // NBT contract shared by the server (who bakes the target) and the client (needle + screen).
    public static final String TAG_CORPSE_ID = "CorpseId";
    public static final String TAG_X = "CorpseX";
    public static final String TAG_Y = "CorpseY";
    public static final String TAG_Z = "CorpseZ";
    public static final String TAG_DIM = "CorpseDim";
    public static final String TAG_OWNER_NAME = "OwnerName";
    // Worn armor at death, baked so the locator screen can render the body as it fell.
    public static final String TAG_HELMET = "Helmet";
    public static final String TAG_CHEST = "Chest";
    public static final String TAG_LEGS = "Legs";
    public static final String TAG_BOOTS = "Boots";

    public CorpseCompassItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // Client-only screen; guard the classload so the server never touches client classes.
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.raiiiden.ragdollified.client.screen.CorpseCompassScreen.open(stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (hasTarget(stack)) {
            Vec3 p = getTargetPos(stack);
            String owner = getOwnerName(stack);
            if (!owner.isEmpty()) {
                tooltip.add(Component.translatable("item.ragdollified.corpse_compass.owner", owner)
                        .withStyle(ChatFormatting.GRAY));
            }
            tooltip.add(Component.translatable("item.ragdollified.corpse_compass.pos",
                            (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    /** Build a bound Corpse Compass stack for the given target. */
    public static ItemStack create(@Nullable UUID corpseId, Vec3 pos,
                                   @Nullable ResourceKey<Level> dim, String ownerName,
                                   ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots) {
        ItemStack stack = new ItemStack(ModItems.CORPSE_COMPASS.get());
        CompoundTag tag = stack.getOrCreateTag();
        if (corpseId != null) tag.putUUID(TAG_CORPSE_ID, corpseId);
        tag.putDouble(TAG_X, pos.x);
        tag.putDouble(TAG_Y, pos.y);
        tag.putDouble(TAG_Z, pos.z);
        if (dim != null) tag.putString(TAG_DIM, dim.location().toString());
        if (ownerName != null) tag.putString(TAG_OWNER_NAME, ownerName);
        putArmor(tag, TAG_HELMET, helmet);
        putArmor(tag, TAG_CHEST, chest);
        putArmor(tag, TAG_LEGS, legs);
        putArmor(tag, TAG_BOOTS, boots);
        return stack;
    }

    private static void putArmor(CompoundTag tag, String key, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) tag.put(key, stack.save(new CompoundTag()));
    }

    /** Worn armor baked at death for the given slot key ({@link #TAG_HELMET} etc.); EMPTY if none. */
    public static ItemStack getArmor(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains(key)) ? ItemStack.of(tag.getCompound(key)) : ItemStack.EMPTY;
    }

    public static boolean hasTarget(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG_X) && tag.contains(TAG_Y) && tag.contains(TAG_Z);
    }

    @Nullable
    public static UUID getCorpseId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.hasUUID(TAG_CORPSE_ID)) ? tag.getUUID(TAG_CORPSE_ID) : null;
    }

    public static Vec3 getTargetPos(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        return new Vec3(tag.getDouble(TAG_X), tag.getDouble(TAG_Y), tag.getDouble(TAG_Z));
    }

    public static String getOwnerName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.getString(TAG_OWNER_NAME) : "";
    }

    /** The dimension the corpse is in, or null if not recorded / unparseable. */
    @Nullable
    public static ResourceKey<Level> getTargetDimension(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_DIM)) return null;
        ResourceLocation loc = ResourceLocation.tryParse(tag.getString(TAG_DIM));
        return loc == null ? null : ResourceKey.create(Registries.DIMENSION, loc);
    }
}
