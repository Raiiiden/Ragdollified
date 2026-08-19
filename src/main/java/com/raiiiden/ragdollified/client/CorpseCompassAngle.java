package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.entity.CorpseEntity;
import com.raiiiden.ragdollified.item.CorpseCompassItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ClampedItemPropertyFunction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import javax.vecmath.Vector3f;
import java.util.UUID;

// Item-model angle property pointing the Corpse Compass needle at its corpse, adapted from vanilla
// with the same wobble maths but reading the stack NBT, so it works with that chunk unloaded.
public class CorpseCompassAngle implements ClampedItemPropertyFunction {

    private final Wobble wobble = new Wobble();
    private final Wobble wobbleRandom = new Wobble();

    @Override
    public float unclampedCall(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
        Entity holder = entity != null ? entity : stack.getEntityRepresentation();
        if (holder == null) return 0.0F;
        if (level == null && holder.level() instanceof ClientLevel cl) level = cl;
        if (level == null) return 0.0F;

        long ticks = level.getGameTime();
        if (!CorpseCompassItem.hasTarget(stack) || !dimensionMatches(stack, holder)) {
            return spinning(seed, ticks);
        }
        return towards(holder, ticks, resolveTarget(level, stack));
    }

    private static Vec3 resolveTarget(ClientLevel level, ItemStack stack) {
        UUID corpseId = CorpseCompassItem.getCorpseId(stack);
        if (corpseId != null) {
            for (Entity candidate : level.entitiesForRendering()) {
                if (candidate instanceof CorpseEntity corpse
                        && corpseId.equals(corpse.getCorpseId())) {
                    return corpse.position();
                }
            }
        }

        int ragdollId = CorpseCompassItem.getRagdollEntityId(stack);
        UUID ownerId = CorpseCompassItem.getOwnerId(stack);
        if (ragdollId >= 0) {
            ClientRagdoll ragdoll = ClientRagdollManager.get(ragdollId);
            if (ragdoll != null && !ragdoll.isDestroyed()
                    && (ownerId == null || ownerId.equals(ragdoll.getPlayerUUID()))) {
                ClientRagdoll.TransformSnapshot snapshot = ragdoll.getSnapshot();
                if (snapshot != null && !snapshot.destroyed) {
                    Vector3f torso = snapshot.cachedTorsoPos;
                    return new Vec3(torso.x, torso.y, torso.z);
                }
            }
        }

        return CorpseCompassItem.getTargetPos(stack);
    }

    private static boolean dimensionMatches(ItemStack stack, Entity holder) {
        ResourceKey<Level> dim = CorpseCompassItem.getTargetDimension(stack);
        // No recorded dimension (shouldn't happen post-1.0): assume match so it still points.
        return dim == null || dim.equals(holder.level().dimension());
    }

    private float towards(Entity holder, long ticks, Vec3 target) {
        double angle = Math.atan2(target.z - holder.getZ(), target.x - holder.getX()) / (Math.PI * 2.0);
        double facing = Mth.positiveModulo(holder.getVisualRotationYInDegrees() / 360.0, 1.0);
        if (holder instanceof Player player && player.isLocalPlayer()) {
            if (wobble.shouldUpdate(ticks)) wobble.update(ticks, 0.5 - (facing - 0.25));
            return Mth.positiveModulo((float) (angle + wobble.rotation), 1.0F);
        }
        return Mth.positiveModulo((float) (0.5 - (facing - 0.25 - angle)), 1.0F);
    }

    private float spinning(int seed, long ticks) {
        if (wobbleRandom.shouldUpdate(ticks)) wobbleRandom.update(ticks, Math.random());
        double d = wobbleRandom.rotation + (double) ((float) hash(seed) / (float) Integer.MAX_VALUE);
        return Mth.positiveModulo((float) d, 1.0F);
    }

    private static int hash(int value) { return value * 1327217883; }

    // Vanilla CompassWobble: critically-damped needle smoothing so it doesn't snap each frame.
    private static final class Wobble {
        double rotation;
        private double deltaRotation;
        private long lastUpdateTick;

        boolean shouldUpdate(long ticks) { return lastUpdateTick != ticks; }

        void update(long ticks, double target) {
            lastUpdateTick = ticks;
            double d = Mth.positiveModulo(target - rotation + 0.5, 1.0) - 0.5;
            deltaRotation += d * 0.1;
            deltaRotation *= 0.8;
            rotation = Mth.positiveModulo(rotation + deltaRotation, 1.0);
        }
    }
}
