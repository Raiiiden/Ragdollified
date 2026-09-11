package com.raiiiden.ragdollified;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class RagdollHitMapper {
    public static final int CENTER_HIT_PART_INDEX = -2;
    public static final int GLOBAL_VELOCITY_KICK_INDEX = -3;

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
        // Extra lift proportional to horizontal impulse, so planted feet don't turn a hit into a mere shove.
        double lift = com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_IMPULSE_VERTICAL_LIFT);
        if (lift > 0.0) {
            vert += lift * base * Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        }
        return new net.minecraft.world.phys.Vec3(
                direction.x * base,
                direction.y * base + vert,
                direction.z * base);
    }

    // Backwards-compat overload: defaults damage to 0 (no damage scaling applied)
    public static net.minecraft.world.phys.Vec3 computeImpulse(
            net.minecraft.world.phys.Vec3 direction, boolean isHeadShot, boolean isTaczBullet) {
        return computeImpulse(direction, isHeadShot, isTaczBullet, 0f);
    }

    public static RagdollPart map(LivingEntity entity, Vec3 hitPos, boolean isHeadShot) {
        return map(entity, hitPos, null, isHeadShot);
    }

    public static RagdollPart map(LivingEntity entity, Vec3 hitPos, Vec3 direction, boolean isHeadShot) {
        return resolve(entity, hitPos, direction, isHeadShot).part;
    }

    // Which part was struck, and where; the impact point is the lever the body turns about.
    // rayOrigin is the projectile's start this tick and can never serve as that lever.
    public static Resolution resolve(LivingEntity entity, Vec3 rayOrigin, Vec3 direction,
                                     boolean isHeadShot) {
        if (entity == null) return new Resolution(RagdollPart.TORSO, null, "none");
        MobModelHelper.ModelType modelType = MobModelHelper.getModelTypeFromEntity(entity);
        if (rayOrigin != null && direction != null && direction.lengthSqr() > 1.0e-6) {
            Vec3 unit = direction.normalize();
            // AccurateHitboxes knows posed limb positions; name the part from the struck limb's centre.
            com.raiiiden.ragdollified.compat.AccurateHitboxesCompat.PartHit exact =
                    accurateHit(entity, rayOrigin, unit);
            if (exact != null) {
                RagdollPart hit = isHeadShot ? RagdollPart.HEAD : mapByPoint(modelType, entity, exact.centre);
                if (hit != null) return new Resolution(hit, exact.entry, "hitboxes");
            }
            double t = raytraceDistance(modelType, entity, rayOrigin, unit);
            Vec3 impact = t >= 0 ? rayOrigin.add(unit.scale(t)) : clipEntity(entity, rayOrigin, unit);
            if (isHeadShot) return new Resolution(RagdollPart.HEAD, impact, "headshot");
            RagdollPart hit = mapByRaytrace(modelType, entity, rayOrigin, direction);
            if (hit != null) return new Resolution(hit, impact, t >= 0 ? "raytrace" : "raytrace/clip");
            if (impact != null) return new Resolution(map(modelType, entity, impact), impact, "bucket/clip");
        }
        if (isHeadShot) return new Resolution(RagdollPart.HEAD, rayOrigin, "headshot/noray");
        return new Resolution(map(modelType, entity, rayOrigin), rayOrigin, "bucket/noray");
    }

    // A part and the world-space entry point, either of which may be null.
    public static final class Resolution {
        public final RagdollPart part;
        public final Vec3 impact;
        // Which resolver answered, for the hit log only.
        public final String source;

        Resolution(RagdollPart part, Vec3 impact, String source) {
            this.part = part == null ? RagdollPart.TORSO : part;
            this.impact = impact;
            this.source = source;
        }

        // The same hit on the same part, moved to a different point on the body.
        public Resolution withImpact(Vec3 moved) {
            return moved == impact ? this : new Resolution(part, moved, source + "+side");
        }
    }

    // A world point in the mob's own frame (X right, Y up from feet, Z forward), as the layout tables use.
    public static Vec3 toBodyLocal(LivingEntity entity, Vec3 point) {
        if (entity == null || point == null) return null;
        return rotateIntoBody(entity, point.x - entity.getX(), point.y - entity.getY(),
                point.z - entity.getZ());
    }

    // A direction in the mob's own frame: same rotation as toBodyLocal, no translation.
    public static Vec3 toBodyLocalDirection(LivingEntity entity, Vec3 direction) {
        if (entity == null || direction == null) return null;
        return rotateIntoBody(entity, direction.x, direction.y, direction.z);
    }

    // Back to world from the mob's frame; the transform is its own inverse (a reflection).
    public static Vec3 fromBodyLocal(LivingEntity entity, double x, double y, double z) {
        if (entity == null) return null;
        Vec3 offset = rotateIntoBody(entity, x, y, z);
        return new Vec3(entity.getX() + offset.x, entity.getY() + offset.y, entity.getZ() + offset.z);
    }

    private static Vec3 rotateIntoBody(LivingEntity entity, double dx, double dy, double dz) {
        float yawRad = (float) Math.toRadians(entity.getVisualRotationYInDegrees());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        return new Vec3(-(dx * cos + dz * sin), dy, -dx * sin + dz * cos);
    }

    // Push the wound across the shot (axis t), the only lever component that produces torque.
    // Adds aim offset (aimSpread) plus obliquity (attackerSideBias), capped at the part's silhouette.
    public static Vec3 biasTowardShooter(LivingEntity entity, RagdollPart part, Vec3 impact,
                                         Vec3 direction, double bias) {
        if (entity == null || impact == null || direction == null) return impact;
        if (direction.lengthSqr() < 1.0e-6) return impact;
        Vec3 local = toBodyLocal(entity, impact);
        Vec3 shot = toBodyLocalDirection(entity, direction.normalize());
        if (local == null || shot == null) return impact;

        // The shot flattened into the ground plane, and the horizontal axis across it. A purely
        // vertical shot has no across-the-body axis to speak of and is left alone.
        double horizontal = Math.sqrt(shot.x * shot.x + shot.z * shot.z);
        if (horizontal < 1.0e-4) return impact;
        double sx = shot.x / horizontal;
        double sz = shot.z / horizontal;
        double tx = -sz;
        double tz = sx;

        double[] box = partBoxLocal(entity, part);
        if (box == null) return impact;
        // How far out along t the part's own silhouette reaches: the box's support in that
        // direction, so the wound is never pushed off the body it landed on.
        double reach = Math.abs(tx) * box[3] + Math.abs(tz) * box[5];
        if (reach <= 0.0) return impact;

        // Where the killer was looking, measured across the shot from the part's centre, and the
        // gain on it.
        double along = (local.x - box[0]) * tx + (local.z - box[2]) * tz;
        double aimed = along * Math.max(0.0,
                RagdollifiedConfig.get(RagdollifiedConfig.HIT_AIM_SPREAD));
        // The floor obliquity puts under it. sx is the sine of the bearing off the mob's facing, so
        // this is zero head-on and the full silhouette square-on, and its sign never turns over.
        double oblique = sx * reach * Math.min(1.0, Math.max(0.0, bias));

        // Eased onto the silhouette with tanh rather than hard-clipped, so the gradient reaches the edge.
        double moved = reach * Math.tanh((aimed + oblique) / reach);
        if (Math.abs(moved) <= Math.abs(along)) return impact;

        double shift = moved - along;
        return fromBodyLocal(entity, local.x + tx * shift, local.y, local.z + tz * shift);
    }

    // One part's box in the mob's frame as {cx, cy, cz, hx, hy, hz}, or null.
    // Mobs without a layout fall back to a quarter of their width.
    private static double[] partBoxLocal(LivingEntity entity, RagdollPart part) {
        if (part == null) return null;
        MobModelHelper.ModelType modelType = MobModelHelper.getModelTypeFromEntity(entity);
        for (PartAABB p : aabbsFor(modelType, entity)) {
            if (p.part == part) {
                return new double[]{
                        (p.minX + p.maxX) * 0.5, (p.minY + p.maxY) * 0.5, (p.minZ + p.maxZ) * 0.5,
                        (p.maxX - p.minX) * 0.5, (p.maxY - p.minY) * 0.5, (p.maxZ - p.minZ) * 0.5};
            }
        }
        double half = entity.getBbWidth() * 0.25;
        return new double[]{0.0, entity.getBbHeight() * 0.5, 0.0, half, half, half};
    }

    // The shot as a segment long enough to cross the entity; overshooting is harmless.
    private static com.raiiiden.ragdollified.compat.AccurateHitboxesCompat.PartHit accurateHit(
            LivingEntity entity, Vec3 origin, Vec3 unit) {
        if (!com.raiiiden.ragdollified.compat.AccurateHitboxesCompat.isAvailable()) return null;
        double reach = origin.distanceTo(entity.position())
                + entity.getBbHeight() + entity.getBbWidth() + 1.0;
        return com.raiiiden.ragdollified.compat.AccurateHitboxesCompat.hitPart(
                entity, origin, origin.add(unit.scale(reach)));
    }

    // Where the ray meets the entity's own bounding box, for the case where it missed every part
    // box: still far better than the ray's origin, which is wherever the shot started.
    private static Vec3 clipEntity(LivingEntity entity, Vec3 origin, Vec3 unit) {
        double reach = origin.distanceTo(entity.position())
                + entity.getBbHeight() + entity.getBbWidth() + 1.0;
        return entity.getBoundingBox().inflate(0.05)
                .clip(origin, origin.add(unit.scale(reach))).orElse(null);
    }

    // Distance along the ray to the first part box, or -1 when it meets none.
    private static double raytraceDistance(MobModelHelper.ModelType modelType, LivingEntity entity,
                                           Vec3 origin, Vec3 unit) {
        PartAABB[] aabbs = aabbsFor(modelType, entity);
        if (aabbs.length == 0) return -1;
        double dx = origin.x - entity.getX();
        double dy = origin.y - entity.getY();
        double dz = origin.z - entity.getZ();
        float yawRad = (float) Math.toRadians(entity.getVisualRotationYInDegrees());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double ox = -(dx * cos + dz * sin);
        double oz = -dx * sin + dz * cos;
        double rx = -(unit.x * cos + unit.z * sin);
        double rz = -unit.x * sin + unit.z * cos;

        double best = -1;
        for (PartAABB p : aabbs) {
            double t = rayAabb(ox, dy, oz, rx, unit.y, rz,
                    p.minX, p.minY, p.minZ, p.maxX, p.maxY, p.maxZ);
            if (t > Double.NEGATIVE_INFINITY && (best < 0 || t < best)) best = t;
        }
        return best;
    }

    // Part whose box holds this world point, or the nearest, for points already known to be on the body.
    private static RagdollPart mapByPoint(MobModelHelper.ModelType modelType, LivingEntity entity,
                                          Vec3 point) {
        PartAABB[] aabbs = aabbsFor(modelType, entity);
        if (aabbs.length == 0) return null;

        double dx = point.x - entity.getX();
        double dy = point.y - entity.getY();
        double dz = point.z - entity.getZ();
        float yawRad = (float) Math.toRadians(entity.getVisualRotationYInDegrees());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double x = -(dx * cos + dz * sin);
        double y = dy;
        double z = -dx * sin + dz * cos;

        RagdollPart nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (PartAABB p : aabbs) {
            double distance = distanceToBoxSq(x, y, z, p);
            if (distance <= 0.0) return p.part;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = p.part;
            }
        }
        return nearest;
    }

    private static double distanceToBoxSq(double x, double y, double z, PartAABB p) {
        double ox = Math.max(p.minX - x, Math.max(0.0, x - p.maxX));
        double oy = Math.max(p.minY - y, Math.max(0.0, y - p.maxY));
        double oz = Math.max(p.minZ - z, Math.max(0.0, z - p.maxZ));
        if (ox <= 0.0 && oy <= 0.0 && oz <= 0.0) return 0.0;
        ox = Math.max(0.0, ox);
        oy = Math.max(0.0, oy);
        oz = Math.max(0.0, oz);
        return ox * ox + oy * oy + oz * oz;
    }

    // Start of the projectile's travel this tick: position() during the hurt event, for TACZ and vanilla.
    public static Vec3 projectileSegmentStart(net.minecraft.world.entity.Entity projectile, Vec3 direction) {
        Vec3 velocity = projectile.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0e-6) return projectile.position();
        // A projectile with no velocity left has already stopped; fall back to where it came from.
        Vec3 previous = new Vec3(projectile.xOld, projectile.yOld, projectile.zOld);
        if (direction == null || direction.lengthSqr() < 1.0e-6) return previous;
        return previous.subtract(direction.scale(0.75));
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
        float yawRad = (float) Math.toRadians(entity.getVisualRotationYInDegrees());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        // Negated to match the layout tables' frame; only the magnitude is used today.
        double rotX = -(localX * cos + localZ * sin);
        double leeway = com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_CENTER_LEEWAY);
        // Zero means off.
        if (leeway <= 0.0) return false;
        return Math.abs(rotX) <= Math.max(0.01, entity.getBbWidth() * leeway);
    }

    // Raytrace-based hit-part resolution

    private record PartAABB(RagdollPart part,
                            double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ) {}

    private static PartAABB box(RagdollPart part, double cx, double cy, double cz,
                                double hx, double hy, double hz) {
        return new PartAABB(part, cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    // AABB layout in the factory's frame: origin at the entity, Y from feet, +X mob's right, +Z forward.
    // The frame is negated above to match the factory's X; each LEFT_ slot's sign follows its factory.
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
                        // Legs meet at the midline rather than overlap,
                        // so a shot between them isn't always the left leg.
                        box(RagdollPart.LEFT_LEG,  -0.125, cy - 0.75, 0,    0.125, 0.45, 0.15),
                        box(RagdollPart.RIGHT_LEG,  0.125, cy - 0.75, 0,    0.125, 0.45, 0.15),
                        // Arm centres match buildHumanoid: (+-0.35, cy - 0.13).
                        box(RagdollPart.LEFT_ARM,  -0.35, cy - 0.13,  0,    0.10, 0.35, 0.10),
                        box(RagdollPart.RIGHT_ARM,  0.35, cy - 0.13,  0,    0.10, 0.35, 0.10),
                };
            }
            case CREEPER: {
                double cy = 1.3;
                float s = scale;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,     0,         cy,                0,         0.30 * s, 0.50 * s, 0.30 * s),
                        box(RagdollPart.HEAD,      0,         cy + 0.75 * s,     0,         0.25 * s, 0.25 * s, 0.25 * s),
                        // buildCreeper adds the front pair into the LEG slots and the rear pair into
                        // the ARM slots, both at positive local X, so these follow it.
                        box(RagdollPart.LEFT_ARM,   0.20 * s, cy - 0.60 * s,    -0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                        box(RagdollPart.RIGHT_ARM, -0.20 * s, cy - 0.60 * s,    -0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                        box(RagdollPart.LEFT_LEG,   0.20 * s, cy - 0.60 * s,     0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                        box(RagdollPart.RIGHT_LEG, -0.20 * s, cy - 0.60 * s,     0.20 * s,  0.12 * s, 0.30 * s, 0.12 * s),
                };
            }
            case QUADRUPED: {
                // spawnYOffset is 0.7 * scale for quadrupeds, and in this entity-facing-local frame the
                // head sits at +Z relative to the torso centre.
                double cy = 0.7 * scale;
                float s = scale;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,     0,         cy,                  0,          0.28 * s, 0.38 * s, 0.48 * s),
                        box(RagdollPart.HEAD,      0,         cy + 0.10 * s,       0.85 * s,   0.20 * s, 0.20 * s, 0.28 * s),
                        // Cow, pig, sheep and cat have positive legX, so LEFT_ sits on the mob's right.
                        // Panda, goat and polar bear have their own tables.
                        box(RagdollPart.LEFT_ARM,   0.20 * s, cy - 0.55 * s,       0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                        box(RagdollPart.RIGHT_ARM, -0.20 * s, cy - 0.55 * s,       0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                        box(RagdollPart.LEFT_LEG,   0.20 * s, cy - 0.55 * s,      -0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                        box(RagdollPart.RIGHT_LEG, -0.20 * s, cy - 0.55 * s,      -0.35 * s,   0.10 * s, 0.28 * s, 0.10 * s),
                };
            }
            case WOLF: {
                double cy = 0.625;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,      0,        cy,          0,        .25,   .21875, .47),
                        box(RagdollPart.HEAD,       0,        cy + .09375, .8125,    .1875, .25,    .21875),
                        box(RagdollPart.LEFT_ARM,   .09375,  cy - .375,   .53125,   .0625, .25,    .0625),
                        box(RagdollPart.RIGHT_ARM, -.09375,  cy - .375,   .53125,   .0625, .25,    .0625),
                        box(RagdollPart.LEFT_LEG,   .09375,  cy - .375,  -.15625,   .0625, .25,    .0625),
                        box(RagdollPart.RIGHT_LEG, -.09375,  cy - .375,  -.15625,   .0625, .25,    .0625),
                };
            }
            case FOX: {
                double cy = 0.469;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,      0,       cy,          0,        .1875, .1875, .34375),
                        box(RagdollPart.HEAD,       0,       cy,          .625,     .25,   .25,   .28125),
                        box(RagdollPart.LEFT_ARM,   .125,   cy - .28125, .21875,   .0625, .1875, .0625),
                        box(RagdollPart.RIGHT_ARM, -.125,   cy - .28125, .21875,   .0625, .1875, .0625),
                        box(RagdollPart.LEFT_LEG,   .125,   cy - .28125,-.21875,   .0625, .1875, .0625),
                        box(RagdollPart.RIGHT_LEG, -.125,   cy - .28125,-.21875,   .0625, .1875, .0625),
                };
            }
            case PANDA: {
                boolean baby = entity.isBaby();
                double cy = baby ? 0.270833 : 0.875;
                double bs = baby ? 1.0 / 3.0 : 1.0;
                double hs = baby ? 1.5 / 2.7 : 1.0;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,      0,             cy,                         0,              .59375*bs, .40625*bs, .8125*bs),
                        box(RagdollPart.HEAD,       0,             cy+(baby?.083333:0),         baby?.440972:1.09375, .53125*hs, .40625*hs, .34375*hs),
                        box(RagdollPart.LEFT_ARM,  -.34375*bs,    cy-.59375*bs,                .5625*bs,      .1875*bs,  .28125*bs, .1875*bs),
                        box(RagdollPart.RIGHT_ARM,  .34375*bs,    cy-.59375*bs,                .5625*bs,      .1875*bs,  .28125*bs, .1875*bs),
                        box(RagdollPart.LEFT_LEG,  -.34375*bs,    cy-.59375*bs,               -.5625*bs,      .1875*bs,  .28125*bs, .1875*bs),
                        box(RagdollPart.RIGHT_LEG,  .34375*bs,    cy-.59375*bs,               -.5625*bs,      .1875*bs,  .28125*bs, .1875*bs),
                };
            }
            case IRON_GOLEM: {
                double cy = 1.515625;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,      0,        cy,           0,       .5625,  .546875, .34375),
                        box(RagdollPart.HEAD,       0,        cy+.828125,   .25,     .25,    .34375,  .3125),
                        box(RagdollPart.LEFT_ARM,  -.6875,   cy-.359375,  -.03125, .125,   .9375,   .1875),
                        box(RagdollPart.RIGHT_ARM,  .6875,   cy-.359375,  -.03125, .125,   .9375,   .1875),
                        box(RagdollPart.LEFT_LEG,  -.28125,  cy-1.015625,  0,       .1875,  .5,      .15625),
                        box(RagdollPart.RIGHT_LEG,  .28125,  cy-1.015625,  0,       .1875,  .5,      .15625),
                };
            }
            case GOAT: {
                boolean baby=entity.isBaby(); double b=baby?.5:1, h=baby?.6:1, cy=baby?.34375:.6875;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,0,cy,0,.34375*b,.4375*b,.53125*b),
                        box(RagdollPart.HEAD,0,cy+(baby?.05:.15625),baby?.409375:.75,.34375*h,.46875*h,.15625*h),
                        box(RagdollPart.LEFT_ARM,-.125*b,cy-.375*b,.3125*b,.09375*b,.3125*b,.09375*b),
                        box(RagdollPart.RIGHT_ARM,.125*b,cy-.375*b,.3125*b,.09375*b,.3125*b,.09375*b),
                        box(RagdollPart.LEFT_LEG,-.125*b,cy-.5*b,-.3125*b,.09375*b,.1875*b,.09375*b),
                        box(RagdollPart.RIGHT_LEG,.125*b,cy-.5*b,-.3125*b,.09375*b,.1875*b,.09375*b)};
            }
            case POLAR_BEAR: {
                boolean baby=entity.isBaby(); double b=baby?.5:1, h=baby?2.0/3.0:1, cy=baby?.50625:1.0125, rs=1.2;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,0,cy,0,.4375*b*rs,.34375*b*rs,.8125*b*rs),
                        box(RagdollPart.HEAD,0,cy+(baby?-.00625:.0375),baby?.65:1.275,.28125*h*rs,.25*h*rs,.3125*h*rs),
                        box(RagdollPart.LEFT_ARM,-.28125*b*rs,cy-.53125*b*rs,.375*b*rs,.125*b*rs,.3125*b*rs,.1875*b*rs),
                        box(RagdollPart.RIGHT_ARM,.28125*b*rs,cy-.53125*b*rs,.375*b*rs,.125*b*rs,.3125*b*rs,.1875*b*rs),
                        box(RagdollPart.LEFT_LEG,-.28125*b*rs,cy-.53125*b*rs,-.5*b*rs,.125*b*rs,.3125*b*rs,.25*b*rs),
                        box(RagdollPart.RIGHT_LEG,.28125*b*rs,cy-.53125*b*rs,-.5*b*rs,.125*b*rs,.3125*b*rs,.25*b*rs)};
            }
            case TURTLE: {
                double b=entity.isBaby()?1.0/6.0:1, cy=entity.isBaby()?.046875:.28125;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,0,cy,0,.59375*b,.28125*b,.625*b),
                        box(RagdollPart.HEAD,0,cy-.0625*b,.8125*b,.1875*b,.15625*b,.1875*b),
                        box(RagdollPart.LEFT_ARM,-.71875*b,cy-.125*b,.40625*b,.40625*b,.03125*b,.15625*b),
                        box(RagdollPart.RIGHT_ARM,.71875*b,cy-.125*b,.40625*b,.40625*b,.03125*b,.15625*b),
                        box(RagdollPart.LEFT_LEG,-.21875*b,cy-.1875*b,-.8125*b,.125*b,.03125*b,.3125*b),
                        box(RagdollPart.RIGHT_LEG,.21875*b,cy-.1875*b,-.8125*b,.125*b,.03125*b,.3125*b)};
            }
            case ENDERMAN: {
                double cy=2.0;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,0,cy,0,.25,.375,.125),
                        box(RagdollPart.HEAD,0,cy+.5625,0,.25,.25,.25),
                        box(RagdollPart.LEFT_ARM,-.3125,cy-.5625,0,.0625,.9375,.0625),
                        box(RagdollPart.RIGHT_ARM,.3125,cy-.5625,0,.0625,.9375,.0625),
                        box(RagdollPart.LEFT_LEG,-.125,cy-1.125,0,.0625,.9375,.0625),
                        box(RagdollPart.RIGHT_LEG,.125,cy-1.125,0,.0625,.9375,.0625)};
            }
            case CAMEL: {
                double b=entity.isBaby()?.45:1,cy=entity.isBaby()?.73078:1.625;
                return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.46875*b,.375*b,.84375*b),box(RagdollPart.HEAD,0,cy+.4375*b,1.125*b,.21875*b,.6875*b,.78125*b),box(RagdollPart.LEFT_ARM,-.30625*b,cy-.96875*b,.625*b,.15625*b,.65625*b,.15625*b),box(RagdollPart.RIGHT_ARM,.30625*b,cy-.96875*b,.625*b,.15625*b,.65625*b,.15625*b),box(RagdollPart.LEFT_LEG,-.30625*b,cy-.96875*b,-.625*b,.15625*b,.65625*b,.15625*b),box(RagdollPart.RIGHT_LEG,.30625*b,cy-.96875*b,-.625*b,.15625*b,.65625*b,.15625*b)};
            }
            case LLAMA: {
                boolean baby=entity.isBaby();double cy=baby?.363636:1.0625;
                if(baby)return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.234375,.142045,.255682),box(RagdollPart.HEAD,0,cy+.34494,.39944,.178571,.426136,.248016),box(RagdollPart.LEFT_ARM,-.099432,cy-.15496,.170455,.056818,.180785,.056818),box(RagdollPart.RIGHT_ARM,.099432,cy-.15496,.170455,.056818,.180785,.056818),box(RagdollPart.LEFT_LEG,-.099432,cy-.15496,-.142045,.056818,.180785,.056818),box(RagdollPart.RIGHT_LEG,.099432,cy-.15496,-.142045,.056818,.180785,.056818)};
                return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.375,.3125,.5625),box(RagdollPart.HEAD,0,cy+.53125,.75,.25,.65625,.3125),box(RagdollPart.LEFT_ARM,-.21875,cy-.625,.375,.125,.4375,.125),box(RagdollPart.RIGHT_ARM,.21875,cy-.625,.375,.125,.4375,.125),box(RagdollPart.LEFT_LEG,-.21875,cy-.625,-.3125,.125,.4375,.125),box(RagdollPart.RIGHT_LEG,.21875,cy-.625,-.3125,.125,.4375,.125)};
            }
            case RABBIT: {
                boolean baby=entity.isBaby();double bs=baby?.4:.6,hs=baby?.5666667:.6,cy=baby?.156:.234;
                return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.1875*bs,.15625*bs,.3125*bs),box(RagdollPart.HEAD,0,cy+(baby?.1575:.141),baby?.1314:.2487,.2*hs,.28125*hs,.2*hs),box(RagdollPart.LEFT_ARM,baby?-.075:-.1125,cy+(baby?-.067:-.10075),baby?.1185:.1777,.0625*bs,.21875*bs,.08333*bs),box(RagdollPart.RIGHT_ARM,baby?.075:.1125,cy+(baby?-.067:-.10075),baby?.1185:.1777,.0625*bs,.21875*bs,.08333*bs),box(RagdollPart.LEFT_LEG,baby?-.075:-.1125,cy+(baby?-.07475:-.112),baby?-.03175:-.0476,.125*bs,.3*bs,.35*bs),box(RagdollPart.RIGHT_LEG,baby?.075:.1125,cy+(baby?-.07475:-.112),baby?-.03175:-.0476,.125*bs,.3*bs,.35*bs)};
            }
            case FROG: {
                double cy=.15625;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.21875,.09375,.28125),box(RagdollPart.HEAD,0,cy+.125,0,.21875,.09375,.28125),box(RagdollPart.LEFT_ARM,-.25,cy-.0625,.15625,.0625,.09375,.09375),box(RagdollPart.RIGHT_ARM,.25,cy-.0625,.15625,.0625,.09375,.09375),box(RagdollPart.LEFT_LEG,-.25,cy-.0625,-.21875,.09375,.09375,.125),box(RagdollPart.RIGHT_LEG,.25,cy-.0625,-.21875,.09375,.09375,.125)};
            }
            case HOGLIN: {
                boolean baby=entity.isBaby();double b=baby?.5:1,h=baby?1.5/1.9:1,cy=baby?.53125:1.0625;
                return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.5*b,.4375*b,.8125*b),box(RagdollPart.HEAD,0,cy+(baby?.1163:-.1423),baby?.5974:1.1317,.4375*h,.5751*h,.5251*h),box(RagdollPart.LEFT_ARM,-.25*b,cy-.625*b,.53125*b,.1875*b,.4375*b,.1875*b),box(RagdollPart.RIGHT_ARM,.25*b,cy-.625*b,.53125*b,.1875*b,.4375*b,.1875*b),box(RagdollPart.LEFT_LEG,-.15625*b,cy-.71875*b,-.625*b,.15625*b,.34375*b,.15625*b),box(RagdollPart.RIGHT_LEG,.15625*b,cy-.71875*b,-.625*b,.15625*b,.34375*b,.15625*b)};
            }
            case SNIFFER: {
                boolean baby=entity.isBaby();double b=baby?.5:1,h=baby?.6:1,cy=baby?.578125:1.15625;
                return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.78125*b,.90625*b,1.25*b),box(RagdollPart.HEAD,0,cy+(baby?-.28125:-.5),baby?.905625:1.87375,.469375*h,.59375*h,.625*h),box(RagdollPart.LEFT_ARM,-.46875*b,cy-.84375*b,.9375*b,.21875*b,.3125*b,.25*b),box(RagdollPart.RIGHT_ARM,.46875*b,cy-.84375*b,.9375*b,.21875*b,.3125*b,.25*b),box(RagdollPart.LEFT_LEG,-.46875*b,cy-.84375*b,-.9375*b,.21875*b,.3125*b,.25*b),box(RagdollPart.RIGHT_LEG,.46875*b,cy-.84375*b,-.9375*b,.21875*b,.3125*b,.25*b)};
            }
            case RAVAGER: {
                double cy=1.625;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.4375,.625,.90625),box(RagdollPart.HEAD,0,cy-.0625,1.5,.5,.625,.5),box(RagdollPart.LEFT_ARM,-.5,cy-.46875,.71875,.25,1.15625,.25),box(RagdollPart.RIGHT_ARM,.5,cy-.46875,.71875,.25,1.15625,.25),box(RagdollPart.LEFT_LEG,-.5,cy-.46875,-.71875,.25,1.15625,.25),box(RagdollPart.RIGHT_LEG,.5,cy-.46875,-.71875,.25,1.15625,.25)};
            }
            case PHANTOM: {
                int size=Math.max(0,Math.round((scale*3.6f-1f)*4.5f));double s=1+.15*size,cy=1.34375*s;
                return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.15625*s,.09375*s,.28125*s),box(RagdollPart.HEAD,0,cy-.0625*s,.375*s,.21875*s,.09375*s,.15625*s),box(RagdollPart.LEFT_ARM,-.75*s,cy+.03125*s,0,.59375*s,.0625*s,.28125*s),box(RagdollPart.RIGHT_ARM,.75*s,cy+.03125*s,0,.59375*s,.0625*s,.28125*s),box(RagdollPart.LEFT_LEG,0,cy+.03125*s,-.46875*s,.09375*s,.0625*s,.1875*s),box(RagdollPart.RIGHT_LEG,0,cy+.03125*s,-.84375*s,.03125*s,.03125*s,.1875*s)};
            }
            case PARROT: {
                double cy=.28125;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.09375,.1875,.09375),box(RagdollPart.HEAD,0,cy+.238125,-.015,.09375,.1875,.1875),box(RagdollPart.LEFT_ARM,-.09375,cy+.00375,-.015,.03125,.15625,.09375),box(RagdollPart.RIGHT_ARM,.09375,cy+.00375,-.015,.03125,.15625,.09375),box(RagdollPart.LEFT_LEG,-.0625,cy-.21875,-.121875,.03125,.0625,.03125),box(RagdollPart.RIGHT_LEG,.0625,cy-.21875,-.121875,.03125,.0625,.03125)};
            }
            case SLIME:
            case MAGMA_CUBE:
                return new PartAABB[]{box(RagdollPart.TORSO,0,.25,0,.25,.25,.25)};
            case SILVERFISH: {
                double cy=.125;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,-.0625,.1875,.125,.09375),box(RagdollPart.HEAD,0,cy-.03125,.15625,.125,.09375,.125),box(RagdollPart.LEFT_LEG,0,cy-.03125,-.25,.09375,.09375,.09375),box(RagdollPart.RIGHT_LEG,0,cy-.0625,-.4375,.0625,.0625,.09375),box(RagdollPart.LEFT_ARM,0,cy-.09375,-.59375,.0625,.03125,.0625),box(RagdollPart.RIGHT_ARM,0,cy-.09375,-.71875,.03125,.03125,.0625)};
            }
            case ENDERMITE: {
                double cy=.125;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.1875,.125,.15625),box(RagdollPart.HEAD,0,cy-.03125,.21875,.125,.09375,.0625),box(RagdollPart.LEFT_LEG,0,cy-.03125,-.1875,.09375,.09375,.03125),box(RagdollPart.RIGHT_LEG,0,cy-.0625,-.25,.03125,.0625,.03125)};
            }
            case ALLAY: {
                double cy=.125;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.09375,.1625,.06875),box(RagdollPart.HEAD,0,cy+.311875,0,.15625,.15625,.15625),box(RagdollPart.LEFT_ARM,-.125,cy+.03125,0,.03125,.125,.0625),box(RagdollPart.RIGHT_ARM,.125,cy+.03125,0,.03125,.125,.0625)};
            }
            case STRIDER: {
                double s=entity.isBaby()?.5:1,cy=entity.isBaby()?.65625:1.3125;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.5*s,.4375*s,.5*s),box(RagdollPart.LEFT_LEG,-.25*s,cy-.8125*s,0,.125*s,.5*s,.125*s),box(RagdollPart.RIGHT_LEG,.25*s,cy-.8125*s,0,.125*s,.5*s,.125*s)};
            }
            case SNOW_GOLEM: {
                double cy=1;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.28125,.28125,.28125),box(RagdollPart.HEAD,0,cy+.5,0,.21875,.21875,.21875),box(RagdollPart.LEFT_LEG,0,cy-.625,0,.34375,.34375,.34375)};
            }
            case BLAZE: {
                return new PartAABB[]{box(RagdollPart.TORSO,0,1.2,0,.25,.25,.25)};
            }
            case SPIDER: {
                double s=entity instanceof net.minecraft.world.entity.monster.CaveSpider?.7:1,cy=.5625*s;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.3125*s,.25*s,.5625*s),box(RagdollPart.HEAD,0,cy,.8125*s,.25*s,.25*s,.25*s)};
            }
            case SHULKER: {
                return new PartAABB[]{box(RagdollPart.TORSO,0,.25,0,.5,.25,.5),box(RagdollPart.HEAD,0,.875,0,.5,.375,.5)};
            }
            case GHAST: {
                return new PartAABB[]{box(RagdollPart.TORSO,0,2.25,0,2.25,2.25,2.25)};
            }
            case VEX: {
                double cy=.35;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.09375,.15625,.0625),box(RagdollPart.HEAD,0,cy+.28125,0,.15625,.15625,.15625),box(RagdollPart.LEFT_ARM,-.125,cy-.109375,0,.05625,.1125,.05625),box(RagdollPart.RIGHT_ARM,.125,cy-.109375,0,.05625,.1125,.05625)};
            }
            case WARDEN: {
                double cy=1.46875;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.5625,.65625,.34375),box(RagdollPart.HEAD,0,cy+1.15625,0,.5,.5,.3125),box(RagdollPart.LEFT_LEG,-.36875,cy-1.0625,0,.1875,.40625,.1875),box(RagdollPart.RIGHT_LEG,.36875,cy-1.0625,0,.1875,.40625,.1875),box(RagdollPart.LEFT_ARM,-.8125,cy-.21875,.0625,.25,.875,.25),box(RagdollPart.RIGHT_ARM,.8125,cy-.21875,.0625,.25,.875,.25)};
            }
            case EQUINE: {
                double rs = entity.getType() == net.minecraft.world.entity.EntityType.HORSE ? 1.1
                        : entity.getType() == net.minecraft.world.entity.EntityType.DONKEY ? 0.87
                        : entity.getType() == net.minecraft.world.entity.EntityType.MULE ? 0.92 : 1.0;
                double cy = rs;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,      0,          cy,             0,           .3125*rs, .3125*rs, .6875*rs),
                        box(RagdollPart.HEAD,       0,          cy + .47*rs,     .69*rs,      .22*rs,   .59*rs,   .44*rs),
                        box(RagdollPart.LEFT_ARM,  -.19*rs,    cy - .66*rs,     .56*rs,      .125*rs,  .344*rs,  .125*rs),
                        box(RagdollPart.RIGHT_ARM,  .19*rs,    cy - .66*rs,     .56*rs,      .125*rs,  .344*rs,  .125*rs),
                        box(RagdollPart.LEFT_LEG,  -.25*rs,    cy - .66*rs,    -.56*rs,      .125*rs,  .344*rs,  .125*rs),
                        box(RagdollPart.RIGHT_LEG,  .25*rs,    cy - .66*rs,    -.56*rs,      .125*rs,  .344*rs,  .125*rs),
                };
            }
            case CHICKEN: {
                double cy = 0.4 * scale;
                float s = scale;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,     0,         cy,                 0,           0.20 * s, 0.22 * s, 0.15 * s),
                        box(RagdollPart.HEAD,      0,         cy + 0.45 * s,      0.20 * s,    0.12 * s, 0.12 * s, 0.12 * s),
                        box(RagdollPart.LEFT_LEG,   0.10 * s, cy - 0.35 * s,      0,           0.06 * s, 0.18 * s, 0.06 * s),
                        box(RagdollPart.RIGHT_LEG, -0.10 * s, cy - 0.35 * s,      0,           0.06 * s, 0.18 * s, 0.06 * s),
                        box(RagdollPart.LEFT_ARM,   0.35 * s, cy + 0.05 * s,      0,           0.05 * s, 0.18 * s, 0.12 * s),
                        box(RagdollPart.RIGHT_ARM, -0.35 * s, cy + 0.05 * s,      0,           0.05 * s, 0.18 * s, 0.12 * s),
                };
            }
            case GUARDIAN: {
                double gs=entity instanceof net.minecraft.world.entity.monster.ElderGuardian?2.35:1,cy=.5*gs;
                return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.4375*gs,.4375*gs,.5*gs),
                        box(RagdollPart.HEAD,0,cy,-.6875*gs,.125*gs,.125*gs,.25*gs),
                        box(RagdollPart.LEFT_LEG,0,cy,-1.09375*gs,.09375*gs,.09375*gs,.21875*gs),
                        box(RagdollPart.RIGHT_LEG,0,cy,-1.625*gs,.0625*gs,.140625*gs,.1875*gs)};
            }
            case SQUID: {
                // Only the first five tentacles get a slot; the other three share the mantle, which is
                // what the ray hits from every angle a tentacle would have covered anyway.
                double cy=1.6;
                RagdollPart[] slots={RagdollPart.HEAD,RagdollPart.LEFT_LEG,RagdollPart.RIGHT_LEG,
                        RagdollPart.LEFT_ARM,RagdollPart.RIGHT_ARM};
                PartAABB[] out=new PartAABB[6];
                out[0]=box(RagdollPart.TORSO,0,cy,0,.375,.5,.375);
                for(int k=0;k<5;k++){
                    double ang=k*Math.PI*2.0/8.0;
                    out[k+1]=box(slots[k],-Math.cos(ang)*.3125,cy-1,-Math.sin(ang)*.3125,.0625,.5625,.0625);
                }
                return out;
            }
            case DOLPHIN: {
                double cy=.345;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.25,.21875,.40625),
                        box(RagdollPart.HEAD,0,cy,.59375,.25,.21875,.1875),
                        box(RagdollPart.LEFT_LEG,0,cy-.0625,-.625,.125,.15625,.34375)};
            }
            case AXOLOTL: {
                double cy=.282;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.25,.15625,.3125),
                        box(RagdollPart.HEAD,0,cy,.46875,.25,.15625,.15625),
                        box(RagdollPart.LEFT_LEG,0,cy,-.6875,.03125,.15625,.375)};
            }
            case FISH: {
                double[] d=fishDimensions(entity);
                PartAABB trunk=box(RagdollPart.TORSO,0,d[7],0,d[0],d[1],d[2]);
                if(d[6]==0) return new PartAABB[]{trunk};
                return new PartAABB[]{trunk,box(RagdollPart.HEAD,0,d[7],-d[6],d[3],d[4],d[5])};
            }
            case WITHER: {
                double cy=1.702;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.625,.40625,.09375),
                        box(RagdollPart.HEAD,0,cy+1.3,.125,.25,.25,.25),
                        box(RagdollPart.LEFT_ARM,-1.125,cy+.925,.25,.1875,.1875,.1875),
                        box(RagdollPart.RIGHT_ARM,1.125,cy+.925,.25,.1875,.1875,.1875),
                        box(RagdollPart.LEFT_LEG,.0625,cy-1.1615,-.2535,.09375,.1875,.09375)};
            }
            case ENDER_DRAGON: {
                double cy=4.5;return new PartAABB[]{box(RagdollPart.TORSO,0,cy,0,.75,.9375,2),
                        box(RagdollPart.HEAD,0,cy-.3125,5.9375,.5,.625,.9375),
                        box(RagdollPart.LEFT_LEG,-.75,cy-1.78125,1.78125,.25,1.59375,.65625),
                        box(RagdollPart.RIGHT_LEG,.75,cy-1.78125,1.78125,.25,1.59375,.65625),
                        box(RagdollPart.LEFT_ARM,-2.5,cy+.5,-.3125,1.75,.25,1.9375),
                        box(RagdollPart.RIGHT_ARM,2.5,cy+.5,-.3125,1.75,.25,1.9375)};
            }
            default:
                return new PartAABB[0];
        }
    }

    // Trunk half extents, tail half extents, the tail's distance behind the trunk and the trunk's height
    // off the ground: the same table RagdollBodyFactory builds each small fish from.
    private static double[] fishDimensions(LivingEntity entity) {
        net.minecraft.world.entity.EntityType<?> type = entity.getType();
        if (type == net.minecraft.world.entity.EntityType.SALMON)
            return new double[]{.046875,.078125,.171875,.046875,.078125,.125,.59375,.251};
        if (type == net.minecraft.world.entity.EntityType.TROPICAL_FISH)
            return new double[]{.03125,.046875,.09375,.015625,.046875,.09375,.375,.126};
        if (type == net.minecraft.world.entity.EntityType.PUFFERFISH)
            return new double[]{.046875,.03125,.046875,0,0,0,0,.126};
        if (type == net.minecraft.world.entity.EntityType.TADPOLE)
            return new double[]{.046875,.03125,.046875,.015625,.03125,.109375,.3125,.126};
        return new double[]{.03125,.078125,.171875,.015625,.0625,.0625,.46875,.126};
    }

    // Ray against every part AABB, returning the one entered first (smallest positive t), or
    // null if it misses them all; the caller then falls back to bbox bucketing.
    private static RagdollPart mapByRaytrace(MobModelHelper.ModelType modelType, LivingEntity entity,
                                             Vec3 hitPos, Vec3 direction) {
        PartAABB[] aabbs = aabbsFor(modelType, entity);
        if (aabbs.length == 0) return null;

        // Translate the ray into the entity-facing-local frame with the same rotation matrix as the
        // bbox-bucket path, so the layout numbers above stay consistent.
        double dx = hitPos.x - entity.getX();
        double dy = hitPos.y - entity.getY();
        double dz = hitPos.z - entity.getZ();
        float yawRad = (float) Math.toRadians(entity.getVisualRotationYInDegrees());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double oxLocal = -(dx * cos + dz * sin);
        double oyLocal = dy;
        double ozLocal = -dx * sin + dz * cos;

        double dxLocal = -(direction.x * cos + direction.z * sin);
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

    // Slab ray/AABB intersection returning entry t, or NEGATIVE_INFINITY on a miss. A negative t counts
    // as a hit at 0, since a bullet's xOld can land inside the hitbox after overshooting in one tick.
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

    // Which side RagdollBodyFactory puts the LEFT_ slots on, in its local X (most use negative).
    // Must agree with the factory, since the index returned is the body the impulse goes to.
    private static boolean leftSlotIsPositiveX(MobModelHelper.ModelType modelType) {
        return switch (modelType) {
            case QUADRUPED, WOLF, FOX, CHICKEN, CREEPER -> true;
            default -> false;
        };
    }

    private static RagdollPart side(MobModelHelper.ModelType modelType, double rotX,
                                    RagdollPart left, RagdollPart right) {
        return leftSlotIsPositiveX(modelType) == (rotX > 0) ? left : right;
    }

    public static RagdollPart map(MobModelHelper.ModelType modelType, LivingEntity entity, Vec3 hitPos) {
        if (entity == null || hitPos == null) return RagdollPart.TORSO;

        // Translate hit into entity-relative coords.
        double localX = hitPos.x - entity.getX();
        double localY = hitPos.y - entity.getY();
        double localZ = hitPos.z - entity.getZ();

        // Rotate the XZ plane so +Z_local aligns with facing: at body yaw 0 forward is south. Limb
        // positions are in body space, so use yBodyRot rather than the look yaw.
        float yawRad = (float) Math.toRadians(entity.getVisualRotationYInDegrees());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double rotX = -(localX * cos + localZ * sin);
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
                if (relY < 0.55f) return side(modelType, rotX, RagdollPart.LEFT_LEG, RagdollPart.RIGHT_LEG);
                if (Math.abs(rotX) > bbW * 0.30) {
                    return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                }
                return RagdollPart.TORSO;
            }
            case CREEPER: {
                if (relY > 0.7f) return RagdollPart.HEAD;
                if (relY < 0.3f) {
                    boolean isFront = rotZ > 0;
                    boolean isLeft = leftSlotIsPositiveX(modelType) == (rotX > 0);
                    // buildCreeper fills LEG slots from the front pair and ARM slots from the rear,
                    // the reverse of the quadrupeds.
                    if (isFront) return isLeft ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                    return isLeft ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                }
                return RagdollPart.TORSO;
            }
            case QUADRUPED:
            case WOLF:
            case FOX:
            case PANDA:
            case GOAT:
            case POLAR_BEAR:
            case CAMEL:
            case LLAMA:
            case RABBIT:
            case FROG:
            case HOGLIN:
            case SNIFFER:
            case RAVAGER:
            case STRIDER:
            case SPIDER:
            case CHICKEN:
            case EQUINE: {
                // For quadrupeds the head extends forward beyond the body's center along
                // +rotZ, so accept hits high or hits that are forward-and-not-low as head.
                if (relY > 0.85f) return RagdollPart.HEAD;
                if (rotZ > bbW * 0.4 && relY > 0.55f) return RagdollPart.HEAD;
                if (relY < 0.5f) {
                    boolean isFront = rotZ > 0;
                    boolean isLeft = leftSlotIsPositiveX(modelType) == (rotX > 0);
                    if (isFront) return isLeft ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                    return isLeft ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                }
                return RagdollPart.TORSO;
            }
            case TURTLE: {
                if (rotZ > bbW*.35f) return RagdollPart.HEAD;
                if (Math.abs(rotX) > bbW*.42f) {
                    return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                }
                if (rotZ < -bbW*.25f) {
                    return side(modelType, rotX, RagdollPart.LEFT_LEG, RagdollPart.RIGHT_LEG);
                }
                return RagdollPart.TORSO;
            }
            case IRON_GOLEM: {
                if (relY > 0.72f) return RagdollPart.HEAD;
                if (relY < 0.45f) return side(modelType, rotX, RagdollPart.LEFT_LEG, RagdollPart.RIGHT_LEG);
                if (Math.abs(rotX) > bbW * 0.24f) {
                    return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                }
                return RagdollPart.TORSO;
            }
            case ENDERMAN: {
                if (relY > 0.82f) return RagdollPart.HEAD;
                if (relY < 0.48f) return side(modelType, rotX, RagdollPart.LEFT_LEG, RagdollPart.RIGHT_LEG);
                if (Math.abs(rotX) > bbW*.2f) return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                return RagdollPart.TORSO;
            }
            case PHANTOM: {
                if (rotZ > bbW*.2f) return RagdollPart.HEAD;
                if (Math.abs(rotX) > bbW*.25f) return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                if (rotZ < -bbW*.2f) return rotZ < -bbW*.55f ? RagdollPart.RIGHT_LEG : RagdollPart.LEFT_LEG;
                return RagdollPart.TORSO;
            }
            case PARROT: {
                if (relY>.68f) return RagdollPart.HEAD;
                if (Math.abs(rotX)>bbW*.25f) return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                if (relY<.35f) return side(modelType, rotX, RagdollPart.LEFT_LEG, RagdollPart.RIGHT_LEG);
                return RagdollPart.TORSO;
            }
            case SLIME:
            case MAGMA_CUBE:
                return RagdollPart.TORSO;
            case SILVERFISH:
            case ENDERMITE: {
                if(rotZ>bbW*.2f)return RagdollPart.HEAD;
                if(rotZ<-bbW*.55f)return RagdollPart.RIGHT_ARM;
                if(rotZ<-bbW*.25f)return RagdollPart.LEFT_LEG;
                return RagdollPart.TORSO;
            }
            case ALLAY: {
                if(relY>.7f)return RagdollPart.HEAD;
                if(Math.abs(rotX)>bbW*.25f)return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                return RagdollPart.TORSO;
            }
            case SNOW_GOLEM: {
                if(relY>.72f)return RagdollPart.HEAD;if(relY<.35f)return RagdollPart.LEFT_LEG;
                if(Math.abs(rotX)>bbW*.25f)return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                return RagdollPart.TORSO;
            }
            case BLAZE:
                return relY>.65f?RagdollPart.TORSO:(side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM));
            case GUARDIAN: {
                if(rotZ<-bbW*.9f)return RagdollPart.RIGHT_LEG;
                if(rotZ<-bbW*.55f)return RagdollPart.LEFT_LEG;
                if(rotZ<-bbW*.2f)return RagdollPart.HEAD;
                return RagdollPart.TORSO;
            }
            case SQUID:
                // Everything under the mantle is tentacle; the ring slot barely matters for an impulse
                // this small, so the nearest of the five that own a slot takes it.
                return relY<.45f?RagdollPart.HEAD:RagdollPart.TORSO;
            case DOLPHIN:
            case AXOLOTL: {
                if(rotZ>bbW*.3f)return RagdollPart.HEAD;
                if(rotZ<-bbW*.3f)return RagdollPart.LEFT_LEG;
                return RagdollPart.TORSO;
            }
            case FISH:
                // The tail owns the HEAD slot on this rig, so a shot from behind drives the tail.
                return rotZ<-bbW*.25f?RagdollPart.HEAD:RagdollPart.TORSO;
            case WITHER: {
                if(relY>.8f){
                    if(Math.abs(rotX)>bbW*.25f)return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                    return RagdollPart.HEAD;
                }
                if(relY<.35f)return RagdollPart.LEFT_LEG;
                return RagdollPart.TORSO;
            }
            case ENDER_DRAGON: {
                if(rotZ>bbW*1.5f)return RagdollPart.HEAD;
                if(Math.abs(rotX)>bbW*.8f)return side(modelType, rotX, RagdollPart.LEFT_ARM, RagdollPart.RIGHT_ARM);
                if(relY<.4f)return side(modelType, rotX, RagdollPart.LEFT_LEG, RagdollPart.RIGHT_LEG);
                return RagdollPart.TORSO;
            }
            default:
                return RagdollPart.TORSO;
        }
    }
}
