package com.raiiiden.ragdollified.network;

import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.CollisionWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.raiiiden.ragdollified.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import javax.vecmath.Vector3f;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sent client → server when the player left-clicks.
 * Server performs a ray test against all bullet bodies and, if a ragdoll part is hit,
 * applies an impulse proportional to the player's attack strength.
 */
public class RagdollRaycastPacket {

    private final Vec3 start;
    private final Vec3 end;

    public RagdollRaycastPacket(Vec3 start, Vec3 end) {
        this.start = start;
        this.end   = end;
    }

    /* ---- encode / decode ---- */

    public static void encode(RagdollRaycastPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.start.x); buf.writeDouble(msg.start.y); buf.writeDouble(msg.start.z);
        buf.writeDouble(msg.end.x);   buf.writeDouble(msg.end.y);   buf.writeDouble(msg.end.z);
    }

    public static RagdollRaycastPacket decode(FriendlyByteBuf buf) {
        Vec3 s = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 e = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new RagdollRaycastPacket(s, e);
    }

    /* ---- server handler ---- */

    public static void handle(RagdollRaycastPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            if (!(sender.level() instanceof ServerLevel serverLevel)) return;

            JbulletWorld jWorld = JbulletWorld.get(serverLevel);

            Vector3f from = new Vector3f((float) msg.start.x, (float) msg.start.y, (float) msg.start.z);
            Vector3f to   = new Vector3f((float) msg.end.x,   (float) msg.end.y,   (float) msg.end.z);

            CollisionWorld.ClosestRayResultCallback cb =
                    new CollisionWorld.ClosestRayResultCallback(from, to);
            jWorld.getDynamicsWorld().rayTest(from, to, cb);

            if (!cb.hasHit()) return;

            CollisionObject hitObj = cb.collisionObject;

            // Try DeathRagdoll (player ragdolls)
            List<DeathRagdollEntity> deathRagdolls =
                    serverLevel.getEntitiesOfClass(DeathRagdollEntity.class,
                            sender.getBoundingBox().inflate(10));
            for (DeathRagdollEntity ragdoll : deathRagdolls) {
                DeathRagdollPhysics phys = ragdoll.getPhysics();
                if (phys == null) continue;
                if (!phys.hasBody(hitObj)) continue;

                int partIdx = phys.ragdollParts.indexOf(hitObj);
                RagdollPart part = RagdollPart.byIndex(partIdx);

                applyHitImpulse((RigidBody) hitObj, cb, sender, part);
                Ragdollified.LOGGER.debug("Player {} hit DeathRagdoll part {}", sender.getName().getString(), part);
                return;
            }

            // Try MobRagdoll
            List<MobRagdollEntity> mobRagdolls =
                    serverLevel.getEntitiesOfClass(MobRagdollEntity.class,
                            sender.getBoundingBox().inflate(10));
            for (MobRagdollEntity ragdoll : mobRagdolls) {
                MobRagdollPhysics phys = ragdoll.getPhysics();
                if (phys == null) continue;
                if (!phys.hasBody(hitObj)) continue;

                int partIdx = phys.ragdollParts.indexOf(hitObj);
                RagdollPart part = RagdollPart.byIndex(partIdx);

                applyHitImpulse((RigidBody) hitObj, cb, sender, part);
                Ragdollified.LOGGER.debug("Player {} hit MobRagdoll part {}", sender.getName().getString(), part);
                return;
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Apply a directional impulse to the hit body.
     * Direction = ray direction (away from player).
     * Strength scales with player's attack cooldown progress and a base force.
     */
    private static void applyHitImpulse(RigidBody body,
                                        CollisionWorld.ClosestRayResultCallback cb,
                                        ServerPlayer player,
                                        RagdollPart part) {
        // Ray direction (normalised hit normal from shooter → ragdoll)
        Vector3f hitNormal = new Vector3f(cb.hitNormalWorld);

        // Use the direction the player was shooting (from → to), normalized
        Vector3f dir = new Vector3f(
                (float)(cb.rayToWorld.x - cb.rayFromWorld.x),
                (float)(cb.rayToWorld.y - cb.rayFromWorld.y),
                (float)(cb.rayToWorld.z - cb.rayFromWorld.z)
        );
        dir.normalize();

        // Scale: base 8, boosted upward slightly, modulated by attack cooldown (0..1)
        float attackCooldown = player.getAttackStrengthScale(0f);
        float baseStrength = 28f * attackCooldown;

        // Extra upward kick for head hits to feel more satisfying
        if (part == RagdollPart.HEAD) {
            baseStrength *= 1.5f;
            dir.y += 0.3f;
        }

        dir.scale(baseStrength);
        body.activate(true);
        body.applyCentralImpulse(dir);
    }
}