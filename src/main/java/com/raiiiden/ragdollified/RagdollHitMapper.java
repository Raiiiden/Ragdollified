package com.raiiiden.ragdollified;

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
        float yawRad = (float) Math.toRadians(entity.getVisualRotationYInDegrees());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double rotX = localX * cos + localZ * sin;
        double centerBand = Math.max(0.05, entity.getBbWidth()
                * com.raiiiden.ragdollified.config.RagdollifiedConfig.get(
                        com.raiiiden.ragdollified.config.RagdollifiedConfig.HIT_CENTER_LEEWAY));
        return Math.abs(rotX) <= centerBand;
    }

    // Raytrace-based hit-part resolution

    private record PartAABB(RagdollPart part,
                            double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ) {}

    private static PartAABB box(RagdollPart part, double cx, double cy, double cz,
                                double hx, double hy, double hz) {
        return new PartAABB(part, cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    // AABB layout in entity-facing-local coords: origin at the entity, +Z forward, Y from the feet.
    // Mirrors the RagdollBodyFactory layouts plus spawnYOffset, so the raytrace hits the real parts.
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
                // spawnYOffset is 0.7 * scale for quadrupeds, and in this entity-facing-local frame the
                // head sits at +Z relative to the torso centre.
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
            case WOLF: {
                double cy = 0.625;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,      0,        cy,          0,        .25,   .21875, .47),
                        box(RagdollPart.HEAD,       0,        cy + .09375, .8125,    .1875, .25,    .21875),
                        box(RagdollPart.LEFT_ARM,  -.09375,  cy - .375,   .53125,   .0625, .25,    .0625),
                        box(RagdollPart.RIGHT_ARM,  .09375,  cy - .375,   .53125,   .0625, .25,    .0625),
                        box(RagdollPart.LEFT_LEG,  -.09375,  cy - .375,  -.15625,   .0625, .25,    .0625),
                        box(RagdollPart.RIGHT_LEG,  .09375,  cy - .375,  -.15625,   .0625, .25,    .0625),
                };
            }
            case FOX: {
                double cy = 0.469;
                return new PartAABB[]{
                        box(RagdollPart.TORSO,      0,       cy,          0,        .1875, .1875, .34375),
                        box(RagdollPart.HEAD,       0,       cy,          .625,     .25,   .25,   .28125),
                        box(RagdollPart.LEFT_ARM,  -.125,   cy - .28125, .21875,   .0625, .1875, .0625),
                        box(RagdollPart.RIGHT_ARM,  .125,   cy - .28125, .21875,   .0625, .1875, .0625),
                        box(RagdollPart.LEFT_LEG,  -.125,   cy - .28125,-.21875,   .0625, .1875, .0625),
                        box(RagdollPart.RIGHT_LEG,  .125,   cy - .28125,-.21875,   .0625, .1875, .0625),
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

    // Ray against every part AABB, returning the one entered first (smallest positive t), or
    // null if it misses them all — the caller then falls back to bbox bucketing.
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
                    boolean isLeft = rotX < 0;
                    if (isFront) return isLeft ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                    return isLeft ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                }
                return RagdollPart.TORSO;
            }
            case TURTLE: {
                if (rotZ > bbW*.35f) return RagdollPart.HEAD;
                if (Math.abs(rotX) > bbW*.42f) {
                    return rotX < 0 ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                }
                if (rotZ < -bbW*.25f) {
                    return rotX < 0 ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                }
                return RagdollPart.TORSO;
            }
            case IRON_GOLEM: {
                if (relY > 0.72f) return RagdollPart.HEAD;
                if (relY < 0.45f) return rotX < 0 ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                if (Math.abs(rotX) > bbW * 0.24f) {
                    return rotX < 0 ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                }
                return RagdollPart.TORSO;
            }
            case ENDERMAN: {
                if (relY > 0.82f) return RagdollPart.HEAD;
                if (relY < 0.48f) return rotX < 0 ? RagdollPart.LEFT_LEG : RagdollPart.RIGHT_LEG;
                if (Math.abs(rotX) > bbW*.2f) return rotX < 0 ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                return RagdollPart.TORSO;
            }
            case PHANTOM: {
                if (rotZ > bbW*.2f) return RagdollPart.HEAD;
                if (Math.abs(rotX) > bbW*.25f) return rotX < 0 ? RagdollPart.LEFT_ARM : RagdollPart.RIGHT_ARM;
                if (rotZ < -bbW*.2f) return rotZ < -bbW*.55f ? RagdollPart.RIGHT_LEG : RagdollPart.LEFT_LEG;
                return RagdollPart.TORSO;
            }
            case PARROT: {
                if (relY>.68f) return RagdollPart.HEAD;
                if (Math.abs(rotX)>bbW*.25f) return rotX<0?RagdollPart.LEFT_ARM:RagdollPart.RIGHT_ARM;
                if (relY<.35f) return rotX<0?RagdollPart.LEFT_LEG:RagdollPart.RIGHT_LEG;
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
                if(Math.abs(rotX)>bbW*.25f)return rotX<0?RagdollPart.LEFT_ARM:RagdollPart.RIGHT_ARM;
                return RagdollPart.TORSO;
            }
            case SNOW_GOLEM: {
                if(relY>.72f)return RagdollPart.HEAD;if(relY<.35f)return RagdollPart.LEFT_LEG;
                if(Math.abs(rotX)>bbW*.25f)return rotX<0?RagdollPart.LEFT_ARM:RagdollPart.RIGHT_ARM;
                return RagdollPart.TORSO;
            }
            case BLAZE:
                return relY>.65f?RagdollPart.TORSO:(rotX<0?RagdollPart.LEFT_ARM:RagdollPart.RIGHT_ARM);
            default:
                return RagdollPart.TORSO;
        }
    }
}
