package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.api.LimbHit;
import com.raiiiden.ragdollified.api.RagdollAmputationApi;
import com.raiiiden.ragdollified.api.RagdollHit;
import com.raiiiden.ragdollified.api.RagdollifiedApi;
import com.raiiiden.ragdollified.network.ModNetwork;
import com.raiiiden.ragdollified.network.RagdollImpulsePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class RagdollClickHandler {

    // Reference damage for the hit curve: a stone sword. Fists barely roll a corpse, netherite throws it.
    private static final double REFERENCE_ATTACK_DAMAGE = 5.0;
    private static final float BASE_STRENGTH = 10f;
    // Blows land off centre by design, but the impact point comes from a raycast against boxes the
    // player cannot see exactly, so this keeps a grazing edge hit from reading as a huge lever.
    private static final double MAX_LEVER = 0.45;

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null) return;
        if (mc.player.isSpectator()) return;

        // A click that is already hitting something real is that thing's click. Without this a
        // player swinging at a mob standing over a corpse punches both.
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) return;

        double reach = reachOf(mc.player);
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        Vec3 endPos = eyePos.add(look.scale(reach));

        // Stop the ray at the first solid block. Ragdolls are not entities and are invisible to the
        // ordinary pick, so without this a body behind a wall is punchable through it.
        BlockHitResult blockHit = mc.level.clip(new ClipContext(
                eyePos, endPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            endPos = blockHit.getLocation();
        }

        // The exact oriented-box intersection, not the nearest part centre: this is what gives both
        // the right part and a real impact point to pivot the body about.
        Optional<RagdollHit> found = RagdollifiedApi.raycast(eyePos, endPos);
        // A loose limb is picked by the same ray. It is a box lying in the world exactly as a
        // ragdoll part is, and a player swinging at an arm on the floor means to hit the arm.
        Optional<LimbHit> limbFound = RagdollAmputationApi.raycastLimbs(eyePos, endPos);
        if (found.isEmpty() && limbFound.isEmpty()) return;

        // Whichever the swing reached first. Without the comparison an arm draped over a body would
        // always lose to the body underneath it, or always win over one standing in front.
        boolean limbIsNearer = found.isEmpty()
                || (limbFound.isPresent() && limbFound.get().distance() < found.get().distance());

        RagdollPart part = limbIsNearer ? limbFound.get().part() : found.get().part();
        if (part == null) return;

        Vec3 direction = look.lengthSqr() > 1.0e-6 ? look.normalize() : new Vec3(0, 0, 1);
        ItemStack weapon = mc.player.getMainHandItem();
        float damage = attackDamageOf(mc.player, weapon);

        // Cooldown, then weapon, then part. A half-charged swing is half a hit, a better weapon is
        // a harder one, and the head is lighter than the chest so the same blow moves it further.
        float cooldown = mc.player.getAttackStrengthScale(0f);
        double strength = BASE_STRENGTH * cooldown
                * Math.sqrt(Math.max(1.0, damage) / REFERENCE_ATTACK_DAMAGE);
        if (part == RagdollPart.HEAD) strength *= 1.25;

        // Slight lift added after scaling, so bodies roll instead of being pressed into the floor.
        Vec3 impulse = direction.scale(strength).add(0.0, strength * 0.18, 0.0);

        if (limbIsNearer) {
            // Local only: limbs aren't synced, so each client already simulates its own copy.
            RagdollAmputationApi.pushLimb(limbFound.get().limbId(), impulse);
            Ragdollified.LOGGER.debug("Client punched loose limb {} ({}) with {} damage",
                    limbFound.get().limbId(), part, damage);
            return;
        }

        RagdollHit hit = found.get();
        Vec3 impact = clampLever(hit);

        // On a modded server, wait for its sequenced broadcast so every observer applies simultaneous
        // pushes in the same order. A vanilla server has none, so the local-only behaviour stays.
        if (!com.raiiiden.ragdollified.config.RagdollifiedConfig.hasServerSnapshot()) {
            ClientRagdollManager.enqueueImpulse(hit.entityId(), part.index,
                    (float) impulse.x, (float) impulse.y, (float) impulse.z, 0, true,
                    impact.x, impact.y, impact.z);
        }

        // Send to server for broadcast to other players (synchronous from main thread).
        try {
            ModNetwork.CHANNEL.sendToServer(new RagdollImpulsePacket(
                    hit.entityId(), part.index,
                    (float) impulse.x, (float) impulse.y, (float) impulse.z, impact, damage));
        } catch (Exception ignored) {
            // Server might not have the mod; that's fine
        }

        Ragdollified.LOGGER.debug("Client punched ragdoll {} part {} with {} damage",
                hit.entityId(), part, damage);
    }

    // Held weapon's hit strength (base damage plus enchantments), read from the player's attribute.
    private static float attackDamageOf(LivingEntity attacker, ItemStack weapon) {
        double base = attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float enchant = weapon.isEmpty() ? 0f
                : EnchantmentHelper.getDamageBonus(weapon, net.minecraft.world.entity.MobType.UNDEFINED);
        return (float) Math.max(0.0, base + enchant);
    }

    private static double reachOf(LivingEntity player) {
        // Forge exposes the real reach, which mods change; the old hardcoded 5 both overreached
        // vanilla survival and underreached anything that extends it.
        var attribute = player.getAttribute(ForgeMod.ENTITY_REACH.get());
        double reach = attribute != null ? attribute.getValue() : 3.0;
        return Math.max(1.0, reach);
    }

    // Keep the lever arm inside the part rather than wherever the ray happened to clip its corner.
    private static Vec3 clampLever(RagdollHit hit) {
        Optional<com.raiiiden.ragdollified.api.RagdollState> state = RagdollifiedApi.getState(hit.entityId());
        if (state.isEmpty()) return hit.position();
        Vec3 centre = state.get().partPositions().get(hit.part());
        if (centre == null) return hit.position();
        Vec3 offset = hit.position().subtract(centre);
        double length = offset.length();
        if (length <= MAX_LEVER || length < 1.0e-6) return hit.position();
        return centre.add(offset.scale(MAX_LEVER / length));
    }
}
