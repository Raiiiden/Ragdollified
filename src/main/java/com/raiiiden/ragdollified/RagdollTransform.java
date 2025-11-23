package com.raiiiden.ragdollified;

import net.minecraft.network.FriendlyByteBuf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

public class RagdollTransform {
    public final int partId;         // index or identifier for which body part this transform belongs to
    public final Vector3f position;  // world-space position
    public final Quat4f rotation;    // world-space orientation (quaternion)

    public RagdollTransform(int partId, float x, float y, float z,
                            float qx, float qy, float qz, float qw) {
        this.partId = partId;
        this.position = new Vector3f(x, y, z);
        this.rotation = new Quat4f(qx, qy, qz, qw);
    }

    public RagdollTransform(int partId, Vector3f pos, Quat4f rot) {
        this.partId = partId;
        this.position = new Vector3f(pos);
        this.rotation = new Quat4f(rot);
    }

    /** Write to network buffer */
    public void writeTo(FriendlyByteBuf buf) {
        buf.writeInt(partId);
        buf.writeFloat(position.x);
        buf.writeFloat(position.y);
        buf.writeFloat(position.z);
        buf.writeFloat(rotation.x);
        buf.writeFloat(rotation.y);
        buf.writeFloat(rotation.z);
        buf.writeFloat(rotation.w);
    }

    /** Read from network buffer */
    public static RagdollTransform readFrom(FriendlyByteBuf buf) {
        int id = buf.readInt();
        float x = buf.readFloat();
        float y = buf.readFloat();
        float z = buf.readFloat();
        float qx = buf.readFloat();
        float qy = buf.readFloat();
        float qz = buf.readFloat();
        float qw = buf.readFloat();
        return new RagdollTransform(id, x, y, z, qx, qy, qz, qw);
    }
}
