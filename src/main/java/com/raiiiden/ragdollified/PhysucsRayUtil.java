package com.raiiiden.ragdollified;

import net.minecraft.world.phys.Vec3;

import javax.vecmath.Vector3f;
//unused class right now
public class PhysucsRayUtil {
    public static boolean intersectRayAABB(Vec3 start, Vec3 end, Vector3f boxCenter, Vector3f half, Vector3f hitOut) {
        Vector3f dir = new Vector3f((float)(end.x - start.x), (float)(end.y - start.y), (float)(end.z - start.z));
        Vector3f origin = new Vector3f((float)start.x, (float)start.y, (float)start.z);
        float tMin = 0.0f;
        float tMax = 1.0f;

        for (int i = 0; i < 3; i++) {
            float o = (i == 0 ? origin.x : (i == 1 ? origin.y : origin.z));
            float d = (i == 0 ? dir.x : (i == 1 ? dir.y : dir.z));
            float min = (i == 0 ? boxCenter.x - half.x : (i == 1 ? boxCenter.y - half.y : boxCenter.z - half.z));
            float max = (i == 0 ? boxCenter.x + half.x : (i == 1 ? boxCenter.y + half.y : boxCenter.z + half.z));
            if (Math.abs(d) < 1e-5f) {
                if (o < min || o > max) return false;
            } else {
                float t1 = (min - o) / d;
                float t2 = (max - o) / d;
                if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return false;
            }
        }

        hitOut.set(origin.x + dir.x * tMin, origin.y + dir.y * tMin, origin.z + dir.z * tMin);
        return true;
    }
}
