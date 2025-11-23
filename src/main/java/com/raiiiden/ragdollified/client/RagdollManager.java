package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;

import javax.annotation.Nullable;
import java.util.*;

public class RagdollManager {
    private static final Map<Integer, ClientRagdoll> RAGDOLLS = new HashMap<>();

    public static void addOrUpdate(int playerEntityId, int ragdollId, RagdollTransform[] transforms, long serverTime) {
        ClientRagdoll r = RAGDOLLS.get(playerEntityId);
        if (r == null) {
            r = new ClientRagdoll(playerEntityId, ragdollId);
            RAGDOLLS.put(playerEntityId, r);
        }
        r.pushUpdate(transforms, serverTime);
    }

    public static void remove(int playerEntityId){
        RAGDOLLS.remove(playerEntityId);
    }

    @Nullable
    public static ClientRagdoll get(int playerEntityId){
        return RAGDOLLS.get(playerEntityId);
    }

    public static Collection<ClientRagdoll> getAll(){
        return new ArrayList<>(RAGDOLLS.values());
    }

    public static class ClientRagdoll {
        public final int playerEntityId;
        public final int ragdollId;

        private final RagdollTransform[] last = new RagdollTransform[6];
        private final RagdollTransform[] current = new RagdollTransform[6];
        private final RagdollTransform[] smoothed = new RagdollTransform[6]; // NEW: Smoothed values
        private long lastTime = 0L;
        private long currentTime = 0L;

        public ClientRagdoll(int playerEntityId, int ragdollId){
            this.playerEntityId = playerEntityId;
            this.ragdollId = ragdollId;
        }

        public void pushUpdate(RagdollTransform[] transforms, long serverTime){
            for (int i=0;i<6;i++){
                last[i] = current[i];
            }
            for (RagdollTransform t : transforms){
                if (t == null) continue;
                int idx = t.partId;
                if (idx >= 0 && idx < 6) current[idx] = t;
            }
            lastTime = currentTime;
            currentTime = serverTime <= 0 ? System.currentTimeMillis() : serverTime;
        }

        private float computeAlpha(float partialTicks){
            if (lastTime <= 0 || currentTime <= lastTime) return 0f;

            long now = System.currentTimeMillis();
            long dt = currentTime - lastTime;

            if (dt <= 0) return 0f;

            float elapsed = (now - lastTime) / (float)dt;
            return Math.max(0f, Math.min(1f, elapsed));
        }

        public RagdollTransform getPartInterpolated(RagdollPart part, float partialTicks){
            int idx = part.index;
            RagdollTransform prev = last[idx];
            RagdollTransform cur  = current[idx];
            if (cur == null && prev == null) return null;
            if (prev == null) return cur;
            if (cur == null) return prev;

            float a = computeAlpha(partialTicks);

            float x = lerp(prev.position.x, cur.position.x, a);
            float y = lerp(prev.position.y, cur.position.y, a);
            float z = lerp(prev.position.z, cur.position.z, a);

            float[] q = slerp(prev.rotation.x, prev.rotation.y, prev.rotation.z, prev.rotation.w,
                    cur.rotation.x, cur.rotation.y, cur.rotation.z, cur.rotation.w, a);

            RagdollTransform interpolated = new RagdollTransform(idx, x, y, z, q[0], q[1], q[2], q[3]);

            // NEW: Apply exponential smoothing to reduce jitter
            if (smoothed[idx] == null) {
                smoothed[idx] = interpolated;
                return interpolated;
            }

            // Smooth factor (0.3f = balanced smoothness and responsiveness)
            float smoothFactor = 0.3f;

            float smoothX = lerp(smoothed[idx].position.x, interpolated.position.x, smoothFactor);
            float smoothY = lerp(smoothed[idx].position.y, interpolated.position.y, smoothFactor);
            float smoothZ = lerp(smoothed[idx].position.z, interpolated.position.z, smoothFactor);

            // Smooth rotation using slerp
            float[] smoothQ = slerp(
                    smoothed[idx].rotation.x, smoothed[idx].rotation.y, smoothed[idx].rotation.z, smoothed[idx].rotation.w,
                    interpolated.rotation.x, interpolated.rotation.y, interpolated.rotation.z, interpolated.rotation.w,
                    smoothFactor
            );

            smoothed[idx] = new RagdollTransform(idx, smoothX, smoothY, smoothZ, smoothQ[0], smoothQ[1], smoothQ[2], smoothQ[3]);
            return smoothed[idx];
        }

        public Map<RagdollPart, RagdollTransform> getAllPartsInterpolated(float partialTicks) {
            Map<RagdollPart, RagdollTransform> map = new EnumMap<>(RagdollPart.class);
            for (RagdollPart part : RagdollPart.values()) {
                map.put(part, getPartInterpolated(part, partialTicks));
            }
            return map;
        }

        private float lerp(float a, float b, float t){ return a + (b - a) * t; }

        private float[] slerp(float x1,float y1,float z1,float w1, float x2,float y2,float z2,float w2, float t){
            float mag1 = (float)Math.sqrt(x1*x1+y1*y1+z1*z1+w1*w1);
            float mag2 = (float)Math.sqrt(x2*x2+y2*y2+z2*z2+w2*w2);
            x1/=mag1; y1/=mag1; z1/=mag1; w1/=mag1;
            x2/=mag2; y2/=mag2; z2/=mag2; w2/=mag2;

            float dot = x1*x2 + y1*y2 + z1*z2 + w1*w2;
            if (dot < 0f) { dot = -dot; x2=-x2; y2=-y2; z2=-z2; w2=-w2; }
            final float DOT_THRESH = 0.9995f;
            if (dot > DOT_THRESH) {
                float rx = x1 + t*(x2-x1);
                float ry = y1 + t*(y2-y1);
                float rz = z1 + t*(z2-z1);
                float rw = w1 + t*(w2-w1);
                float m = (float)Math.sqrt(rx*rx+ry*ry+rz*rz+rw*rw);
                return new float[]{rx/m, ry/m, rz/m, rw/m};
            }

            double theta0 = Math.acos(dot);
            double theta = theta0 * t;
            double sinTheta = Math.sin(theta);
            double sinTheta0 = Math.sin(theta0);

            float s0 = (float)(Math.cos(theta) - dot * sinTheta / sinTheta0);
            float s1 = (float)(sinTheta / sinTheta0);

            float rx = s0*x1 + s1*x2;
            float ry = s0*y1 + s1*y2;
            float rz = s0*z1 + s1*z2;
            float rw = s0*w1 + s1*w2;
            return new float[]{rx, ry, rz, rw};
        }

        public boolean isActive(){
            for (RagdollTransform t : current) if (t != null) return true;
            return false;
        }
    }
}