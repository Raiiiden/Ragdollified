package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.LimbAnchors;
import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.client.ClientDetachedLimb;
import com.raiiiden.ragdollified.client.ClientDetachedLimbManager;
import com.raiiiden.ragdollified.client.ClientMobModelHelper;
import com.raiiiden.ragdollified.client.ClientMobTextureCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.function.Supplier;

// Server to client: a limb came off a living entity, so build a loose one.
// Model, texture and scale come from the local entity; pose and push are server-decided.
public class LimbSpawnPacket {

    private final int limbId;
    private final int sourceEntityId;
    private final byte partIndex;
    private final double posX, posY, posZ;
    private final float rotX, rotY, rotZ, rotW;
    private final float velX, velY, velZ;
    private final float angVelX, angVelY, angVelZ;
    private final int lifetimeTicks;

    public LimbSpawnPacket(int limbId, int sourceEntityId, RagdollPart part,
                           Vec3 position, Quat4f rotation, Vec3 velocity, Vec3 angularVelocity,
                           int lifetimeTicks) {
        this(limbId, sourceEntityId, (byte) part.index,
                position.x, position.y, position.z,
                rotation.x, rotation.y, rotation.z, rotation.w,
                (float) velocity.x, (float) velocity.y, (float) velocity.z,
                (float) angularVelocity.x, (float) angularVelocity.y, (float) angularVelocity.z,
                lifetimeTicks);
    }

    private LimbSpawnPacket(int limbId, int sourceEntityId, byte partIndex,
                            double posX, double posY, double posZ,
                            float rotX, float rotY, float rotZ, float rotW,
                            float velX, float velY, float velZ,
                            float angVelX, float angVelY, float angVelZ,
                            int lifetimeTicks) {
        this.limbId = limbId;
        this.sourceEntityId = sourceEntityId;
        this.partIndex = partIndex;
        this.posX = posX; this.posY = posY; this.posZ = posZ;
        this.rotX = rotX; this.rotY = rotY; this.rotZ = rotZ; this.rotW = rotW;
        this.velX = velX; this.velY = velY; this.velZ = velZ;
        this.angVelX = angVelX; this.angVelY = angVelY; this.angVelZ = angVelZ;
        this.lifetimeTicks = lifetimeTicks;
    }

    public static void encode(LimbSpawnPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.limbId);
        buf.writeInt(msg.sourceEntityId);
        buf.writeByte(msg.partIndex);
        buf.writeDouble(msg.posX);
        buf.writeDouble(msg.posY);
        buf.writeDouble(msg.posZ);
        buf.writeFloat(msg.rotX);
        buf.writeFloat(msg.rotY);
        buf.writeFloat(msg.rotZ);
        buf.writeFloat(msg.rotW);
        buf.writeFloat(msg.velX);
        buf.writeFloat(msg.velY);
        buf.writeFloat(msg.velZ);
        buf.writeFloat(msg.angVelX);
        buf.writeFloat(msg.angVelY);
        buf.writeFloat(msg.angVelZ);
        buf.writeVarInt(msg.lifetimeTicks);
    }

    public static LimbSpawnPacket decode(FriendlyByteBuf buf) {
        return new LimbSpawnPacket(
                buf.readInt(), buf.readInt(), buf.readByte(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readVarInt());
    }

    public static void handle(LimbSpawnPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(LimbSpawnPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        RagdollPart part = RagdollPart.byIndex(msg.partIndex);
        if (part == null || !part.isSeverable()) return;

        // The entity is alive, so every client that got this packet has it. Without it there is no
        // way to know what the limb should look like, and a grey box would be worse than nothing.
        if (!(level.getEntity(msg.sourceEntityId) instanceof LivingEntity entity)) return;

        boolean isPlayer = entity instanceof Player;
        MobModelHelper.ModelType modelType = isPlayer
                ? MobModelHelper.ModelType.HUMANOID_STANDARD
                : ClientMobModelHelper.getActualModelType(entity);
        if (!isPlayer && !MobModelHelper.isHumanoidModelType(modelType)) return;

        String mobType = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        Quat4f rotation = new Quat4f(msg.rotX, msg.rotY, msg.rotZ, msg.rotW);
        Vector3f halfExtents = toVecmath(LimbAnchors.halfExtents(part, entity.isBaby()));

        ClientDetachedLimb.SpawnData data = new ClientDetachedLimb.SpawnData(
                msg.limbId, msg.sourceEntityId, part, modelType, mobType,
                isPlayer, entity.isBaby(), isPlayer ? 1.0f : entity.getBbHeight() / 1.8f,
                isPlayer ? entity.getUUID() : null,
                new Vec3(msg.posX, msg.posY, msg.posZ), rotation,
                new Vec3(msg.velX, msg.velY, msg.velZ),
                new Vec3(msg.angVelX, msg.angVelY, msg.angVelZ),
                halfExtents, msg.lifetimeTicks);
        if (!isPlayer) {
            data.texture = ClientMobTextureCache.getTextureForDeadMob(msg.sourceEntityId);
        }
        ClientDetachedLimbManager.enqueueSpawn(data);
    }

    private static Vector3f toVecmath(org.joml.Vector3f v) {
        return new Vector3f(v.x, v.y, v.z);
    }
}
