package com.raiiiden.ragdollified;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class RagdollHitMapper {
    public static final int CENTER_HIT_PART_INDEX = -2;

    private RagdollHitMapper() {}
    public static net.minecraft.world.phys.Vec3 computeImpulse(
            net.minecraft.world.phys.Vec3 direction, boolean isHeadShot, boolean isTaczBullet, float damage) {
        return computeImpulse(direction, isHeadShot, isTaczBullet, false, damage);
    }

    public static net.minecraft.world.phys.Vec3 computeImpulse(
            net.minecraft.world.phys.Vec3 direction, boolean isHeadShot, boolean isTaczBullet,
            boolean isMelee, float damage) {
        if (direction == null || direction.lengthSqr() < 1.0e-6) return null;
        double base;
        if (isMelee) {
            base = com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                    com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_MELEE);
        } else if (isTaczBullet) {
            base = isHeadShot
                    ? com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                            com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_HEADSHOT)
                    : com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                            com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_BODY);
        } else {
            base = com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                    com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_VANILLA_PROJECTILE);
        }
        if (damage > 0f
                && com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                        com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_DAMAGE_SCALING)) {
            double ref = com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                    com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_DAMAGE_REFERENCE);
            base *= Math.sqrt(damage / ref);
        }
        double vert = com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_VERTICAL_BIAS);
        return new net.minecraft.world.phys.Vec3(
                direction.x * base,
                direction.y * base + vert,
                direction.z * base);
    }

    // Backwards-compat overload — defaults damage to 0 (no damage scaling applied)
    public static net.minecraft.world.phys.Vec3 computeImpulse(
            net.minecraft.world.phys.Vec3 direction, boolean isHeadShot, boolean isTaczBullet) {
        return computeImpulse(direction, isHeadShot, isTaczBullet, 0f);
    }

    public static RagdollPart map(LivingEntity entity, Vec3 hitPos, boolean isHeadShot) {
        return map(entity, hitPos, null, isHeadShot);
    }

    public static RagdollPart map(LivingEntity entity, Vec3 hitPos, Vec3 direction, boolean isHeadShot) {
        if (entity == null) return RagdollPart.TORSO;
        if (isHeadShot) return RagdollPart.HEAD;
        MobModelHelper.ModelType modelType = MobModelHelper.getModelTypeFromEntity(entity);
        if (direction != null && direction.lengthSqr() > 1.0e-6) {
            RagdollPart hit = mapByRaytrace(modelType, entity, hitPos, direction);
            if (hit != null) return hit;
        }
        return map(modelType, entity, hitPos);
    }

    public static boolean isCenteredHit(LivingEntity entity, Vec3 hitPos, boolean isHeadShot) {
        RagdollPart part = map(entity, hitPos, isHeadShot);
        return isCenteredHit(entity, hitPos, isHeadShot, part);
    }

    public static boolean isCenteredHit(LivingEntity entity, Vec3 hitPos, boolean isHeadShot, RagdollPart part) {
        if (entity == null || hitPos == null || isHeadShot) return false;
        if (part != RagdollPart.TORSO) return false;
        double localX = hitPos.x - entity.getX();
        double localZ = hitPos.z - entity.getZ();
        float yawRad = (float) Math.toRadians(entity.getYRot());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double rotX = localX * cos + localZ * sin;
        double centerBand = Math.max(0.05, entity.getBbWidth()
                * com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                        com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_CENTER_LEEWAY));
        return Math.abs(rotX) <= centerBand;
    }

    // ============================
    // Raytrace-based hit-part resolution
    // ============================

    private record PartAABB(RagdollPart part,
                            double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ) {}

    private static PartAABB box(RagdollPart part, double cx, double cy, double cz,
                                double hx, double hy, double hz) {
        return new PartAABB(part, cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    /**
     * AABB layout in entity-facing-local coords (entity X/Z position at origin, +Z =
     * entity's forward direction, Y in WORLD-Y minus entity.getY() i.e. relative to feet).
     * Numbers mirror {@link RagdollBodyFactory#buildHumanoid} / buildQuadruped /
     * buildChicken / buildCreeper layouts plus the spawnYOffset applied in
     * {@link com.raiiiden.ragdollified.client.ClientRagdoll#createRagdollBodies} so the
     * raytrace tests against where the body parts actually are.
     */
    private static PartAABB[] aabbsFor(MobModelHelper.ModelType modelType, LivingEntity entity) {
        float scale = Math.max(0.001f, entity.getBbHeight() / 1.8f);
        switch (modelType) {
            case HUMANOID_STANDARD:
            case HUMANOID_SKELETON:
            case HUMANOID_DROWNED:
            case ILLAGER: {
                // Spawn pos Y = entity.y + 1.3 (humanoid spawnYOffset), so the torso
                // center Y is at 1.3 above feet. Other parts offset from there.
                double cy = 1.3;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,     0,     cy,         0,    0.25, 0.40, 0.15),
                        box(RagdollPart.HEAD,      0,     cy + 0.55,  0,    0.20, 0.20, 0.20),
                        box(RagdollPart.LEFT_LEG,  -0.10, cy - 0.75,  0,    0.15, 0.45, 0.15),
                        box(RagdollPart.RIGHT_LEG,  0.10, cy - 0.75,  0,    0.15, 0.45, 0.15),
                        box(RagdollPart.LEFT_ARM,  -0.40, cy + 0.05,  0,    0.10, 0.35, 0.10),
                        box(RagdollPart.RIGHT_ARM,  0.40, cy + 0.05,  0,    0.10, 0.35, 0.10),
                };
            }
            case CREEPER: {
                double cy = 1.3;
                float s = scale;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,     0,         cy,                0,         0.30 * s, 0.50 * s, 0.30 * s),
                        box(RagdollPart.HEAD,      0,         cy + 0.75 * s,     0,         0.25 * s, 0.25 * s, 0.25 * s),
                        box(RagdollPart.LEFT_ARM,  -0.20 * s, cy - 0.60 * s,    -0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                        box(RagdollPart.RIGHT_ARM,  0.20 * s, cy - 0.60 * s,    -0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                        box(RagdollPart.LEFT_LEG,  -0.20 * s, cy - 0.60 * s,     0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                        box(RagdollPart.RIGHT_LEG,  0.20 * s, cy - 0.60 * s,     0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                };
            }
            case QUADRUPED: {
                // spawnYOffset = 0.7 * scale for quadrupeds. Head extends forward (-Z in
                // entity local since entity faces -Z, but we work in entity-facing-local
                // where +Z is forward — so head at +Z relative to torso center).
                double cy = 0.7 * scale;
                float s = scale;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,     0,         cy,                  0,          0.28 * s, 0.38 * s, 0.48 * s),
                        box(RagdollPart.HEAD,      0,         cy + 0.10 * s,       0.85 * s,   0.20 * s, 0.20 * s, 0.28 * s),
                        box(RagdollPart.LEFT_ARM,  -0.20 * s, cy - 0.55 * s,       0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                        box(RagdollPart.RIGHT_ARM,  0.20 * s, cy - 0.55 * s,       0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                        box(RagdollPart.LEFT_LEG,  -0.20 * s, cy - 0.55 * s,      -0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                        box(RagdollPart.RIGHT_LEG,  0.20 * s, cy - 0.55 * s,      -0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                };
            }
            case CHICKEN: {
                double cy = 0.4 * scale;
                float s = scale;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,     0,         cy,                 0,           0.20 * s, 0.22 * s, 0.15 * s),
                        box(RagdollPart.HEAD,      0,         cy + 0.45 * s,      0.20 * s,    0.12 * s, 0.12 * s, 0.12 * s),
                        box(RagdollPart.LEFT_LEG,  -0.10 * s, cy - 0.35 * s,      0,           0.06 * s, 0.18 * s, 0.06 * s),
                        box(RagdollPart.RIGHT_LEG,  0.10 * s, cy - 0.35 * s,      0,           0.06 * s, 0.18 * s, 0.06 * s),
                        box(RagdollPart.LEFT_ARM,  -0.35 * s, cy + 0.05 * s,      0,           0.05 * s, 0.18 * s, 0.12 * s),
                        box(RagdollPart.RIGHT_ARM,  0.35 * s, cy + 0.05 * s,      0,           0.05 * s, 0.18 * s, 0.12 * s),
                };
            }
            default:
                return new PartAABB[0];
        }
    }

    /**
     * Run the bullet ray against each part AABB, return the part it enters first
     * (smallest positive t). Returns null if the ray misses every box — caller falls
     * back to bbox bucketing.
     */
    private static RagdollPart mapByRaytrace(MobModelHelper.ModelType modelType, LivingEntity entity,
                                             Vec3 hitPos, Vec3 direction) {
        PartAABB[] aabbs = aabbsFor(modelType, entity);
        if (aabbs.length == 0) return null;

        // Translate ray into entity-facing-local frame: entity X/Z at origin, Y relative
        // to entity feet, +Z = entity forward. Same rotation matrix as the bbox-bucket
        // path so the layout numbers above stay consistent.
        double dx = hitPos.x - entity.getX();
        double dy = hitPos.y - entity.getY();
        double dz = hitPos.z - entity.getZ();
        float yawRad = (float) Math.toRadians(entity.getYRot());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double oxLocal = dx * cos + dz * sin;
        double oyLocal = dy;
        double ozLocal = -dx * sin + dz * cos;

        double dxLocal = direction.x * cos + direction.z * sin;
        double dyLocal = direction.y;
        double dzLocal = -direction.x * sin + direction.z * cos;

        RagdollPart bestPart = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (PartAABB p : aabbs) {
            double t = rayAabb(oxLocal, oyLocal, ozLocal, dxLocal, dyLocal, dzLocal,
                    p.minX, p.minY, p.minZ, p.maxX, p.maxY, p.maxZ);
            if (t > Double.NEGATIVE_INFINITY && t < bestT) {
                bestT = t;
                bestPart = p.part;
            }
        }
        return bestPart;
    }

    /**
     * Slab-method ray-AABB intersection. Returns the t at which the ray first hits
     * the box (entry point), or {@link Double#NEGATIVE_INFINITY} if the ray misses.
     * Accepts negative t when the origin is already inside the box (bullet xOld can be
     * inside the entity hitbox if the bullet overshot in one tick) — in that case the
     * box is considered "hit" with t=0.
     */
    private static double rayAabb(double ox, double oy, double oz,
                                  double dx, double dy, double dz,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;

        if (Math.abs(dx) > 1.0e-9) {
            double t1 = (minX - ox) / dx;
            double t2 = (maxX - ox) / dx;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        } else if (ox < minX || ox > maxX) {
            return Double.NEGATIVE_INFINITY;
        }
        if (Math.abs(dy) > 1.0e-9) {
            double t1 = (minY - oy) / dy;
            double t2 = (maxY - oy) / dy;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        } else if (oy < minY || oy > maxY) {
            return Double.NEGATIVE_INFINITY;
        }
        if (Math.abs(dz) > 1.0e-9) {
            double t1 = (minZ - oz) / dz;
            double t2 = (maxZ - oz) / dz;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        } else if (oz < minZ || oz > maxZ) {
            return Double.NEGATIVE_INFINITY;
        }
        if (tMax < tMin) return Double.NEGATIVE_INFINITY;
        // Origin already inside the box → tMin is negative; treat as hit at t=0 so
        // a bullet that xOld'd one tick past the entity still maps to the right part.
        return Math.max(tMin, 0.0);
    }

    public static RagdollPart map(MobModelHelper.ModelType modelType, LivingEntity entity, Vec3 hitPos) {
        if (entity == null || hitPos == null) return RagdollPart.TORSO;

        // Translate hit into entity-relative coords.
        double localX = hitPos.x - entity.getX();
        double localY = hitPos.y - entity.getY();
        double localZ = hitPos.z - entity.getZ();

        // Rotate the XZ plane so +Z_local aligns with the entity's facing direction.
        // MC convention: yRot=0 → look dir (0,0,1) = south. For an arbitrary yRot, the look
        // dir is (-sin(yaw), 0, cos(yaw)). The matrix below sends that look dir to (0,0,1).
        float yawRad = (float) Math.toRadians(entity.getYRot());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double rotX = localX * cos + localZ * sin;
        double rotZ = -localX * sin + localZ * cos;

        float bbH = entity.getBbHeight();
        float bbW = Math.max(0.4f, entity.getBbWidth());
        float relY = bbH > 0.001f ? (float) (localY / bbH) : 0.5f;

        switch (modelType) {
            case HUMANOID_STANDARD:
            case HUMANOID_SKELETON:
            case HUMANOID_DROWNED:
            case ILLAGER: {
                if (relY > 0.85f) return RagdollPart.HEAD;
                if (relY < 0.55f) return rotX < 0 ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                if (Math.abs(rotX) > bbW * 0.30) {
                    return rotX < 0 ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                }
                return RagdollPart.TORSO;
            }
            case CREEPER: {
                if (relY > 0.7f) return RagdollPart.HEAD;
                if (relY < 0.3f) {
                    boolean isFront = rotZ > 0;
                    boolean isLeft = rotX < 0;
                    if (isFront) return isLeft ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                    return isLeft ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                }
                return RagdollPart.TORSO;
            }
            case QUADRUPED:
            case CHICKEN: {
                // For quadrupeds the head extends forward beyond the body's center along
                // +rotZ, so accept hits high or hits that are forward-and-not-low as head.
                if (relY > 0.85f) return RagdollPart.HEAD;
                if (rotZ > bbW * 0.4 && relY > 0.55f) return RagdollPart.HEAD;
                if (relY < 0.5f) {
                    boolean isFront = rotZ > 0;
                    boolean isLeft = rotX < 0;
                    if (isFront) return isLeft ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                    return isLeft ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                }
                return RagdollPart.TORSO;
            }
            default:
                return RagdollPart.TORSO;
        }
    }
}
