package com.raiiiden.ragdollified.network;

import com.raiiiden.ragdollified.MobPoseCapture;
import com.raiiiden.ragdollified.MobModelHelper;
import com.raiiiden.ragdollified.RagdollHitMapper;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientJbulletWorld;
import com.raiiiden.ragdollified.client.ClientMobModelHelper;
import com.raiiiden.ragdollified.client.EntityRenderCaptureHandler;
import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

// Sent to clients when an entity dies server-side, carrying authoritative spawn data (exact
// position, velocity in blocks/second, armor) so every client builds the same ragdoll.
public class RagdollSpawnPacket {

    private final int originalEntityId;
    private final boolean isPlayer;
    private final String mobType;
    private final MobModelHelper.ModelType modelType;
    private final float scale;
    private final String playerUUID; // empty for mobs
    private final String playerName;
    private final double posX, posY, posZ;
    private final float yRot, xRot;
    private final double velX, velY, velZ;
    private final boolean isSwimming;
    private final boolean isBaby;
    private final ItemStack helmet, chestplate, leggings, boots;
    // Sheep state (ignored for non-sheep mobs). Packed into a single byte:
    // bit 0 = wasSheared, bits 1..4 = dyeColorId (0..15). bit 5 reserved.
    private final byte sheepState;
    // Server-authoritative directional hit info, captured from ServerRagdollHitTracker and baked here
    // so no client races the death packet. hitPartIndex -1 means no hit info and no impulse.
    private final byte hitPartIndex;
    private final float hitImpulseX, hitImpulseY, hitImpulseZ;
    // Impact point relative to the entity origin. Zero means "no lever arm known", which
    // falls back to the old torque-free centre-of-mass application.
    private final float hitOffsetX, hitOffsetY, hitOffsetZ;
    // Overlay-state bits for mob-specific extras drawn over the base model: bit0 charged creeper,
    // bit1 saddled pig, bits 2-7 reserved, zero meaning no overlay.
    private final byte overlayState;
    // Villager profession state, empty type meaning no profession. Registry-key strings so mod-added
    // biomes and professions survive without a remap; level 1..5, 0 unknown.
    private final String villagerType;
    private final String villagerProfession;
    private final byte villagerLevel;

    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                posX, posY, posZ, yRot, xRot, velX, velY, velZ, isSwimming,
                helmet, chestplate, leggings, boots, (byte) 0,
                (byte) -1, 0f, 0f, 0f, (byte) 0);
    }

    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                              byte sheepState) {
        this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                posX, posY, posZ, yRot, xRot, velX, velY, velZ, isSwimming,
                helmet, chestplate, leggings, boots, sheepState,
                (byte) -1, 0f, 0f, 0f, (byte) 0);
    }

    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                              byte sheepState,
                              byte hitPartIndex, float hitImpulseX, float hitImpulseY, float hitImpulseZ) {
        this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                posX, posY, posZ, yRot, xRot, velX, velY, velZ, isSwimming,
                helmet, chestplate, leggings, boots, sheepState,
                hitPartIndex, hitImpulseX, hitImpulseY, hitImpulseZ, (byte) 0,
                "", "", (byte) 0);
    }

    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                              byte sheepState,
                              byte hitPartIndex, float hitImpulseX, float hitImpulseY, float hitImpulseZ,
                              byte overlayState) {
        this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                posX, posY, posZ, yRot, xRot, velX, velY, velZ, isSwimming,
                helmet, chestplate, leggings, boots, sheepState,
                hitPartIndex, hitImpulseX, hitImpulseY, hitImpulseZ, overlayState,
                "", "", (byte) 0);
    }

    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                              byte sheepState,
                              byte hitPartIndex, float hitImpulseX, float hitImpulseY, float hitImpulseZ,
                              byte overlayState,
                              String villagerType, String villagerProfession, byte villagerLevel) {
        this(originalEntityId, isPlayer, mobType, scale, playerUUID, playerName,
                posX, posY, posZ, yRot, xRot, velX, velY, velZ, isSwimming, false,
                helmet, chestplate, leggings, boots, sheepState,
                hitPartIndex, hitImpulseX, hitImpulseY, hitImpulseZ, overlayState,
                villagerType, villagerProfession, villagerLevel);
    }

    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming, boolean isBaby,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                              byte sheepState,
                              byte hitPartIndex, float hitImpulseX, float hitImpulseY, float hitImpulseZ,
                              byte overlayState,
                              String villagerType, String villagerProfession, byte villagerLevel) {
        this(originalEntityId, isPlayer, mobType, resolveModelType(isPlayer, mobType), scale,
                playerUUID, playerName, posX, posY, posZ, yRot, xRot,
                velX, velY, velZ, isSwimming, isBaby,
                helmet, chestplate, leggings, boots, sheepState,
                hitPartIndex, hitImpulseX, hitImpulseY, hitImpulseZ, overlayState,
                villagerType, villagerProfession, villagerLevel);
    }

    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType,
                              MobModelHelper.ModelType modelType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming, boolean isBaby,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                              byte sheepState,
                              byte hitPartIndex, float hitImpulseX, float hitImpulseY, float hitImpulseZ,
                              byte overlayState,
                              String villagerType, String villagerProfession, byte villagerLevel) {
        this(originalEntityId, isPlayer, mobType, modelType, scale, playerUUID, playerName,
                posX, posY, posZ, yRot, xRot, velX, velY, velZ, isSwimming, isBaby,
                helmet, chestplate, leggings, boots, sheepState,
                hitPartIndex, hitImpulseX, hitImpulseY, hitImpulseZ,
                0f, 0f, 0f,
                overlayState, villagerType, villagerProfession, villagerLevel);
    }

    // Canonical form. hitOffset is the impact point relative to the entity origin, the lever arm that
    // turns a hit into rotation; without it every impulse is torque-free and bodies never tip.
    public RagdollSpawnPacket(int originalEntityId, boolean isPlayer, String mobType,
                              MobModelHelper.ModelType modelType, float scale,
                              String playerUUID, String playerName,
                              double posX, double posY, double posZ,
                              float yRot, float xRot,
                              double velX, double velY, double velZ,
                              boolean isSwimming, boolean isBaby,
                              ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                              byte sheepState,
                              byte hitPartIndex, float hitImpulseX, float hitImpulseY, float hitImpulseZ,
                              float hitOffsetX, float hitOffsetY, float hitOffsetZ,
                              byte overlayState,
                              String villagerType, String villagerProfession, byte villagerLevel) {
        this.hitOffsetX = hitOffsetX;
        this.hitOffsetY = hitOffsetY;
        this.hitOffsetZ = hitOffsetZ;
        this.originalEntityId = originalEntityId;
        this.isPlayer = isPlayer;
        this.mobType = mobType;
        this.modelType = modelType != null ? modelType : resolveModelType(isPlayer, mobType);
        this.scale = scale;
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.yRot = yRot;
        this.xRot = xRot;
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
        this.isSwimming = isSwimming;
        this.isBaby = isBaby;
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
        this.sheepState = sheepState;
        this.hitPartIndex = hitPartIndex;
        this.hitImpulseX = hitImpulseX;
        this.hitImpulseY = hitImpulseY;
        this.hitImpulseZ = hitImpulseZ;
        this.overlayState = overlayState;
        this.villagerType = villagerType != null ? villagerType : "";
        this.villagerProfession = villagerProfession != null ? villagerProfession : "";
        this.villagerLevel = villagerLevel;
    }

    public boolean isPlayer() { return isPlayer; }

    // Pack wasSheared (bit 0) + dyeColorId (bits 1..4, 0..15) into one byte.
    public static byte packSheepState(boolean wasSheared, int dyeColorId) {
        return (byte) ((wasSheared ? 1 : 0) | ((dyeColorId & 0xF) << 1));
    }

    public static boolean unpackSheared(byte s) { return (s & 1) != 0; }
    public static int unpackDyeColorId(byte s) { return (s >> 1) & 0xF; }

    public static boolean unpackChargedCreeper(byte s) { return (s & 0x1) != 0; }
    public static boolean unpackSaddledPig(byte s) { return (s & 0x2) != 0; }

    private static MobModelHelper.ModelType resolveModelType(boolean isPlayer, String mobType) {
        return isPlayer ? MobModelHelper.ModelType.HUMANOID_STANDARD : MobModelHelper.getModelTypeFromMobType(mobType);
    }

    private static MobModelHelper.ModelType readModelType(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        MobModelHelper.ModelType[] values = MobModelHelper.ModelType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MobModelHelper.ModelType.UNSUPPORTED;
    }

    public static void encode(RagdollSpawnPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.originalEntityId);
        buf.writeBoolean(msg.isPlayer);
        buf.writeUtf(msg.mobType);
        buf.writeVarInt(msg.modelType.ordinal());
        buf.writeFloat(msg.scale);
        buf.writeUtf(msg.playerUUID);
        buf.writeUtf(msg.playerName);
        buf.writeDouble(msg.posX);
        buf.writeDouble(msg.posY);
        buf.writeDouble(msg.posZ);
        buf.writeFloat(msg.yRot);
        buf.writeFloat(msg.xRot);
        buf.writeDouble(msg.velX);
        buf.writeDouble(msg.velY);
        buf.writeDouble(msg.velZ);
        buf.writeBoolean(msg.isSwimming);
        buf.writeBoolean(msg.isBaby);
        buf.writeItem(msg.helmet);
        buf.writeItem(msg.chestplate);
        buf.writeItem(msg.leggings);
        buf.writeItem(msg.boots);
        buf.writeByte(msg.sheepState);
        buf.writeByte(msg.hitPartIndex);
        buf.writeFloat(msg.hitImpulseX);
        buf.writeFloat(msg.hitImpulseY);
        buf.writeFloat(msg.hitImpulseZ);
        buf.writeFloat(msg.hitOffsetX);
        buf.writeFloat(msg.hitOffsetY);
        buf.writeFloat(msg.hitOffsetZ);
        buf.writeByte(msg.overlayState);
        buf.writeUtf(msg.villagerType);
        buf.writeUtf(msg.villagerProfession);
        buf.writeByte(msg.villagerLevel);
    }

    public static RagdollSpawnPacket decode(FriendlyByteBuf buf) {
        return new RagdollSpawnPacket(
                buf.readInt(),
                buf.readBoolean(),
                buf.readUtf(),
                readModelType(buf),
                buf.readFloat(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readItem(), buf.readItem(), buf.readItem(), buf.readItem(),
                buf.readByte(),
                buf.readByte(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readByte(),
                buf.readUtf(), buf.readUtf(), buf.readByte()
        );
    }

    public static void handle(RagdollSpawnPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(RagdollSpawnPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        net.minecraft.world.entity.Entity worldEntity = level.getEntity(msg.originalEntityId);
        MobModelHelper.ModelType modelType = msg.modelType;
        // The packet type comes from the entity id, all the server can see, so a client that still has
        // the entity prefers its real model class — except for models we cannot identify, like villagers.
        if (!msg.isPlayer && worldEntity instanceof net.minecraft.world.entity.LivingEntity living) {
            MobModelHelper.ModelType actual = ClientMobModelHelper.getActualModelType(living);
            if (MobModelHelper.isSupportedModelType(actual)) {
                modelType = actual;
            }
            // Preserve renderer-owned textures and custom geometry before the dying entity can
            // leave the client level. Optional mod-specific handling lives behind compat helpers.
            EntityRenderCaptureHandler.captureRenderState(living);
        }

        if (!msg.isPlayer && !MobModelHelper.isSupportedModelType(modelType)) {
            Ragdollified.LOGGER.info(
                    "Skipping ragdoll for unsupported mob {} - no matching ragdoll body/render",
                    msg.mobType);
            return;
        }

        // Snapshot the damage visuals while the dying entity is still around: this packet often beats
        // the client's own LivingDeathEvent, and whichever runs first has to do it.
        if (worldEntity instanceof net.minecraft.world.entity.LivingEntity dying) {
            ClientRagdollManager.captureCompatVisuals(dying);
        }

        // Get captured pose if available locally
        MobPoseCapture.MobPose capturedPose = MobPoseCapture.getPose(msg.originalEntityId);

        UUID uuid = null;
        if (!msg.playerUUID.isEmpty()) {
            try { uuid = UUID.fromString(msg.playerUUID); } catch (Exception ignored) {}
        }

        // Prefer the server-baked hit values, which cannot race the hurt event, falling back to the
        // client tracker only when the server had no capture.
        int hitPartIndex;
        Vec3 hitImpulse;
        // Lever arm for the impulse. Zero means the server had no impact point (older server,
        // explosion kick, …) and the client falls back to a centre-of-mass impulse.
        Vec3 hitOffset = null;
        if (msg.hitPartIndex >= 0
                || msg.hitPartIndex == RagdollHitMapper.CENTER_HIT_PART_INDEX
                || msg.hitPartIndex == RagdollHitMapper.GLOBAL_VELOCITY_KICK_INDEX) {
            hitPartIndex = msg.hitPartIndex;
            hitImpulse = new Vec3(msg.hitImpulseX, msg.hitImpulseY, msg.hitImpulseZ);
            if (msg.hitOffsetX != 0f || msg.hitOffsetY != 0f || msg.hitOffsetZ != 0f) {
                hitOffset = new Vec3(msg.hitOffsetX, msg.hitOffsetY, msg.hitOffsetZ);
            }
        } else {
            com.raiiiden.ragdollified.client.RagdollHitTracker.ResolvedHit hit = null;
            if (worldEntity instanceof net.minecraft.world.entity.LivingEntity living) {
                hit = com.raiiiden.ragdollified.client.RagdollHitTracker.resolveAndPlan(living, null);
            }
            hitPartIndex = hit != null
                    ? (hit.centered ? RagdollHitMapper.CENTER_HIT_PART_INDEX : hit.part.index)
                    : -1;
            hitImpulse = hit != null ? hit.impulse : null;
        }

        boolean wasSheared = unpackSheared(msg.sheepState);
        int dyeColorId = unpackDyeColorId(msg.sheepState);
        boolean chargedCreeper = unpackChargedCreeper(msg.overlayState);
        boolean saddledPig = unpackSaddledPig(msg.overlayState);
        ResourceLocation capturedTexture = msg.isPlayer ? null
                : com.raiiiden.ragdollified.client.ClientMobTextureCache
                        .getTextureForDeadMob(msg.originalEntityId);

        ClientRagdoll.SpawnData data = new ClientRagdoll.SpawnData(
                msg.originalEntityId,
                msg.isPlayer,
                msg.mobType,
                modelType,
                msg.scale,
                uuid,
                msg.playerName,
                msg.helmet, msg.chestplate, msg.leggings, msg.boots,
                new Vec3(msg.posX, msg.posY, msg.posZ),
                msg.yRot, msg.xRot,
                new Vec3(msg.velX, msg.velY, msg.velZ),
                capturedPose,
                msg.isSwimming,
                msg.isBaby,
                capturedTexture,
                hitPartIndex, hitImpulse, hitOffset,
                wasSheared, dyeColorId,
                chargedCreeper, saddledPig,
                msg.villagerType, msg.villagerProfession, msg.villagerLevel
        );

        // Enqueue for the physics worker. processSpawnQueue destroys any existing local death-event
        // spawn for this entity id first, on that thread, so there is no race.
        ClientRagdollManager.enqueueCoordinatedSpawn(data);
    }
}
