package com.raiiiden.ragdollified.physics.jbullet;

import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.raiiiden.ragdollified.physics.PhysicsShape;

import javax.vecmath.Vector3f;

final class JBulletShape implements PhysicsShape {

    final CollisionShape shape;
    private final BoxShape box;

    JBulletShape(CollisionShape shape) {
        this.shape = shape;
        this.box = shape instanceof BoxShape b ? b : null;
    }

    @Override
    public void getHalfExtents(Vector3f out) {
        // Bullet carves its margin out of the requested extents and adds it back at collision time,
        // so "with margin" is the size the solver resolves against.
        if (box != null) box.getHalfExtentsWithMargin(out);
        else out.set(0f, 0f, 0f);
    }

    @Override
    public boolean isBox() {
        return box != null;
    }
}
