package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.Ragdollified;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.api.CameraOptions;
import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import com.raiiiden.ragdollified.mixin.CameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// Owns API and automatic death-camera attachments. Forge's camera-angle event is the frame boundary:
// the body's smoothed state is prepared here and reused by the renderer, keeping head and camera one.
@Mod.EventBusSubscriber(modid = Ragdollified.MODID, value = Dist.CLIENT)
public final class ClientRagdollCamera {
    // Just outside the front face of a normal 0.5-block-wide player head.
    private static final float EYE_FORWARD_OFFSET = 0.325F;
    private static final float EYE_VERTICAL_OFFSET = 0.05F;
    private static final double CLIP_MARGIN = 0.05D;
    // Half-size of the box kept clear around the eye. The near plane is 0.05 blocks out, so anything
    // closer renders as see-through terrain. Same radius vanilla probes for the third-person camera.
    private static final double NEAR_RADIUS = 0.1D;
    private static final double ESCAPE_STEP = 0.05D;
    private static final double MAX_ESCAPE = 0.75D;
    // Order matters: a head resting on the floor buries the eye in the block below far more
    // often than in a wall, so try straight up first and leave down for last.
    private static final Vec3[] ESCAPE_DIRECTIONS = {
            new Vec3(0D, 1D, 0D),
            new Vec3(1D, 0D, 0D), new Vec3(-1D, 0D, 0D),
            new Vec3(0D, 0D, 1D), new Vec3(0D, 0D, -1D),
            new Vec3(0D, -1D, 0D)
    };

    private record Attachment(int entityId, RagdollPart part, CameraOptions options) {}
    private record ResolvedAttachment(ClientRagdoll ragdoll, RagdollPart part, CameraOptions options) {}

    private static volatile Attachment attachment;
    // Render-thread frame token shared with ClientRagdollRenderer.
    private static long renderFrame;
    // Identity guard used to finalize the same event after other camera-angle subscribers.
    private static ViewportEvent.ComputeCameraAngles activeAngleEvent;
    // Ragdoll whose head the camera is currently looking out of this frame, or -1.
    private static int headViewRagdollId = -1;

    private ClientRagdollCamera() {}

    public static boolean attach(int entityId, RagdollPart part, CameraOptions options) {
        if (part == null || !ClientRagdollManager.hasPendingOrActiveRagdoll(entityId)) return false;
        attachment = new Attachment(entityId, part, options != null ? options : CameraOptions.DEFAULT);
        return true;
    }

    public static void detach() { attachment = null; }
    public static void detach(int entityId) {
        Attachment current = attachment;
        if (current != null && current.entityId == entityId) attachment = null;
    }

    public static long currentRenderFrame() {
        return renderFrame;
    }

    // Runs right after vanilla camera setup: Forge applies the returned yaw, pitch and roll before the
    // world view is built, so this composes with other mods and needs no GameRenderer injection.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        long frame = ++renderFrame;
        activeAngleEvent = null;
        headViewRagdollId = -1;
        Minecraft minecraft = Minecraft.getInstance();
        ResolvedAttachment current = resolveAttachment(minecraft);
        if (current == null) return;

        ClientRagdoll.TransformSnapshot snapshot = current.ragdoll.getSnapshot();
        if (snapshot == null || snapshot.destroyed) return;

        float partialTick = (float) event.getPartialTick();
        RagdollTransform transform;
        if (current.options.smoothed()) {
            current.ragdoll.updateSmoothedRenderState(snapshot, partialTick, frame);
            transform = current.ragdoll.getSmoothedTransform(current.part);
        } else {
            transform = snapshot.getInterpolatedTransform(current.part, partialTick);
        }
        if (transform == null) return;

        apply(event, minecraft, transform, current.options);
        activeAngleEvent = event;
        if (current.part == RagdollPart.HEAD && minecraft.options.getCameraType().isFirstPerson()) {
            headViewRagdollId = current.ragdoll.getId();
        }
    }

    // True when the local camera is inside this ragdoll's head, so the renderer can skip it.
    // Local only — every other client still draws the full body.
    public static boolean isHeadHidden(int ragdollId) {
        return headViewRagdollId == ragdollId;
    }

    // Rebuild Camera's public quaternion and direction vectors from the final event angles.
    // Running last picks up recoil, sway, and effects added by other Forge subscribers.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFinalizeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (activeAngleEvent != event) return;
        activeAngleEvent = null;

        Quaternionf rotation = new Quaternionf().rotationYXZ(
                (float) Math.toRadians(-event.getYaw()),
                (float) Math.toRadians(event.getPitch()),
                (float) Math.toRadians(event.getRoll()));
        Camera camera = event.getCamera();
        camera.rotation().set(rotation);
        camera.getLookVector().set(0F, 0F, 1F).rotate(rotation);
        camera.getUpVector().set(0F, 1F, 0F).rotate(rotation);
        camera.getLeftVector().set(1F, 0F, 0F).rotate(rotation);
    }

    private static ResolvedAttachment resolveAttachment(Minecraft minecraft) {
        Attachment current = attachment;
        if (current != null) {
            ClientRagdoll ragdoll = ClientRagdollManager.get(current.entityId);
            if (ragdoll != null && !ragdoll.isDestroyed()) {
                return new ResolvedAttachment(ragdoll, current.part, current.options);
            }
            // A queued spawn is not invalid; retain the attachment until its worker creates it.
            if (ClientRagdollManager.hasPendingOrActiveRagdoll(current.entityId)) return null;
            attachment = null;
        }

        // The normal death camera uses the exact same code path as API attachments.
        if (minecraft.player == null
                || !RagdollifiedConfig.get(RagdollifiedConfig.ENABLE_PLAYER_RAGDOLLS)
                || !RagdollifiedConfig.ENABLE_DEATH_CAMERA.get()
                || !minecraft.player.isDeadOrDying()) {
            return null;
        }
        ClientRagdoll ragdoll = ClientRagdollManager.get(minecraft.player.getId());
        return ragdoll == null || ragdoll.isDestroyed()
                ? null
                : new ResolvedAttachment(ragdoll, RagdollPart.HEAD, CameraOptions.DEFAULT);
    }

    private static void apply(ViewportEvent.ComputeCameraAngles event, Minecraft minecraft,
                              RagdollTransform transform, CameraOptions options) {
        Camera camera = event.getCamera();
        Quaternionf headRotation = new Quaternionf(transform.rotation.x, transform.rotation.y,
                transform.rotation.z, transform.rotation.w).normalize();
        // Minecraft's camera looks along local +Z, while the ragdoll model's face points -Z.
        // Keep the eye on the face side, but turn the camera around to look away from the head.
        Quaternionf cameraRotation = new Quaternionf(headRotation).rotateY((float) Math.PI);

        // Camera#setRotation creates rotationYXZ(-yaw, pitch, 0). Decomposing in the same order
        // preserves the ragdoll's full head orientation, including roll.
        Vector3f euler = cameraRotation.getEulerAnglesYXZ(new Vector3f());
        float yaw = (float) Math.toDegrees(-euler.y);
        float pitch = (float) Math.toDegrees(euler.x);
        float roll = (float) Math.toDegrees(euler.z);

        Vector3f eyeOffset = headRotation.transform(new Vector3f(
                0F, EYE_VERTICAL_OFFSET, -EYE_FORWARD_OFFSET));
        Vec3 anchor = new Vec3(transform.position.x, transform.position.y, transform.position.z);
        Vec3 desired = anchor.add(eyeOffset.x, eyeOffset.y, eyeOffset.z);
        Vec3 position = options.avoidBlocks()
                ? clipToClearPosition(minecraft, anchor, desired)
                : desired;

        ((CameraAccessor) camera).ragdollified$setPosition(position.x, position.y, position.z);

        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(roll);
    }

    // Sweep from the head centre out to the desired eye instead of popping between blocks, then check
    // the eye is not inside geometry. The escape pass is what keeps a prone body's camera out of ground.
    private static Vec3 clipToClearPosition(Minecraft minecraft, Vec3 anchor, Vec3 desired) {
        Level level = minecraft.level;
        if (level == null) return desired;
        Entity cameraEntity = minecraft.getCameraEntity();

        Vec3 offset = desired.subtract(anchor);
        double length = offset.length();
        Vec3 position = anchor;
        if (length >= 1.0E-6D) {
            // Sweep the near plane's corners, not just the centre ray: a centre ray reports clear while
            // half the frustum is buried in a wall, which let the world render through the screen edges.
            double allowed = length;
            for (int corner = 0; corner < 8; corner++) {
                Vec3 nudge = new Vec3(
                        ((corner & 1) * 2 - 1) * NEAR_RADIUS,
                        ((corner >> 1 & 1) * 2 - 1) * NEAR_RADIUS,
                        ((corner >> 2 & 1) * 2 - 1) * NEAR_RADIUS);
                Vec3 from = anchor.add(nudge);
                BlockHitResult hit = level.clip(new ClipContext(
                        from, desired.add(nudge), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, cameraEntity));
                if (hit.getType() == HitResult.Type.MISS) continue;
                allowed = Math.min(allowed, Math.max(0D, hit.getLocation().distanceTo(from) - CLIP_MARGIN));
            }
            position = anchor.add(offset.scale(Math.min(1D, allowed / length)));
        }
        return escapeSolids(level, position);
    }

    // Push the eye to the nearest spot with clear space around it, giving up if nothing within
    // MAX_ESCAPE is free — a wildly displaced camera reads worse than a briefly clipped one.
    private static Vec3 escapeSolids(Level level, Vec3 position) {
        if (isClear(level, position)) return position;
        for (double distance = ESCAPE_STEP; distance <= MAX_ESCAPE; distance += ESCAPE_STEP) {
            for (Vec3 direction : ESCAPE_DIRECTIONS) {
                Vec3 candidate = position.add(
                        direction.x * distance, direction.y * distance, direction.z * distance);
                if (isClear(level, candidate)) return candidate;
            }
        }
        return position;
    }

    // Block collisions only — entities never occlude the camera, and neither does the border.
    private static boolean isClear(Level level, Vec3 position) {
        AABB box = new AABB(
                position.x - NEAR_RADIUS, position.y - NEAR_RADIUS, position.z - NEAR_RADIUS,
                position.x + NEAR_RADIUS, position.y + NEAR_RADIUS, position.z + NEAR_RADIUS);
        for (VoxelShape shape : level.getBlockCollisions(null, box)) {
            if (!shape.isEmpty()) return false;
        }
        return true;
    }
}
