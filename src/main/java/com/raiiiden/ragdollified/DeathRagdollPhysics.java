package com.raiiiden.ragdollified;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.Generic6DofConstraint;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import com.raiiiden.ragdollified.network.DeathRagdollStartPacket;
import com.raiiiden.ragdollified.network.DeathRagdollUpdatePacket;
import com.raiiiden.ragdollified.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

public class DeathRagdollPhysics {
    private final DeathRagdollEntity entity;
    private final JbulletWorld manager;
    private final DiscreteDynamicsWorld world;
    private int stuckCheckCounter = 0;
    private Vector3f lastTorsoPosition = new Vector3f();

    public final List<RigidBody> ragdollParts = new ArrayList<>(6);
    private final List<TypedConstraint> ragdollJoints = new ArrayList<>(5);
    private final List<CollisionObject> localStaticCollision = new ArrayList<>();

    private final int networkRagdollId;
    private BlockPos lastCollisionCenter = BlockPos.ZERO;
    private boolean ignoreNext;

    public DeathRagdollPhysics(DeathRagdollEntity entity, JbulletWorld manager) {
        this.entity = entity;
        this.manager = manager;
        this.world = manager.getDynamicsWorld();
        this.networkRagdollId = entity.getId();

        createRagdollBodies();
        updateLocalWorldCollision();

        int tick = ((ServerLevel)entity.level()).getServer().getTickCount();
        ModNetwork.CHANNEL.send(
                PacketDistributor.ALL.noArg(),
                new DeathRagdollStartPacket(entity.getId(), tick, getRagdollTransforms())
        );
    }

    public void update() {
        RagdollTransform[] transforms = getRagdollTransforms();

        int tick = ((ServerLevel)entity.level()).getServer().getTickCount();
        ModNetwork.CHANNEL.send(
                PacketDistributor.ALL.noArg(),
                new DeathRagdollUpdatePacket(networkRagdollId, tick, transforms)
        );

        // Keep your existing velocity clamping but add angular velocity limiting
        for (RigidBody r : ragdollParts) {
            Vector3f vel = new Vector3f();
            r.getLinearVelocity(vel);

            // Keep your existing vertical clamp
            if (vel.y < -80f) vel.y = -80f;

            // Keep your existing speed clamp
            float speed = vel.length();
            if (speed > 90f) {
                vel.scale(90f / speed);
            }
            r.setLinearVelocity(vel);

            // NEW: Just limit angular velocity to prevent spinning jitter
            Vector3f angVel = new Vector3f();
            r.getAngularVelocity(angVel);
            float angSpeed = angVel.length();
            if (angSpeed > 8f) {  // Reasonable limit, not too restrictive
                angVel.scale(8f / angSpeed);
                r.setAngularVelocity(angVel);
            }
        }

        applyFluidForces();
        checkCollisionsForParts();
        updateLocalWorldCollision();
        correctInterpenetrations();
    }

    public void applyInitialVelocity(Vector3f velocity) {
        for (RigidBody body : ragdollParts) {
            body.setLinearVelocity(new Vector3f(velocity));
        }
    }

    public Vector3f getTorsoPosition() {
        if (ragdollParts.isEmpty()) {
            return new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
        }
        Transform t = new Transform();
        ragdollParts.get(0).getMotionState().getWorldTransform(t);
        return t.origin;
    }

    private void createRagdollBodies() {
        float x = entity.getXRot();
        if (entity.getPose() == Pose.SWIMMING) {
            x = 90;
        }

        Vector3f pos = new Vector3f((float) entity.getX(), (float) entity.getY() + 1.3f, (float) entity.getZ());
        Quaternionf q = new Quaternionf().rotateXYZ(
                (float) Math.toRadians(x),
                (float) Math.toRadians(180 - entity.getYRot()),
                0f
        );
        Quat4f qq = new Quat4f(q.x, q.y, q.z, q.w);

        java.util.function.Function<Vector3f, Vector3f> worldOffset = (local) -> {
            Vector3f result = new Vector3f(local);
            org.joml.Vector3f temp = new org.joml.Vector3f(result.x, result.y, result.z);
            q.transform(temp);
            result = new Vector3f(temp.x, temp.y, temp.z);
            result.add(pos);
            return result;
        };

        // Scale initial velocity appropriately (matching PlayerPhysics scale of 20)
        Vector3f initialVel = new Vector3f(
                (float) entity.getDeltaMovement().x * 20,
                (float) entity.getDeltaMovement().y * 20,
                (float) entity.getDeltaMovement().z * 20
        );

        // Create all 6 body parts
        ragdollParts.add(createRagdollPart(new BoxShape(new Vector3f(0.25f, 0.4f, 0.15f)),
                pos, qq, 8, initialVel)); // Torso

        ragdollParts.add(createRagdollPart(new BoxShape(new Vector3f(0.2f, 0.2f, 0.2f)),
                worldOffset.apply(new Vector3f(0f, 0.55f, 0f)), qq, 4, initialVel)); // Head

        ragdollParts.add(createRagdollPart(new BoxShape(new Vector3f(0.15f, 0.45f, 0.15f)),
                worldOffset.apply(new Vector3f(-0.1f, -0.75f, 0f)), qq, 6, initialVel)); // Left Leg

        ragdollParts.add(createRagdollPart(new BoxShape(new Vector3f(0.15f, 0.45f, 0.15f)),
                worldOffset.apply(new Vector3f(0.1f, -0.75f, 0f)), qq, 6, initialVel)); // Right Leg

        ragdollParts.add(createRagdollPart(new BoxShape(new Vector3f(0.1f, 0.35f, 0.1f)),
                worldOffset.apply(new Vector3f(-0.4f, 0.05f, 0f)), qq, 4, initialVel)); // Left Arm

        ragdollParts.add(createRagdollPart(new BoxShape(new Vector3f(0.1f, 0.35f, 0.1f)),
                worldOffset.apply(new Vector3f(0.4f, 0.05f, 0f)), qq, 4, initialVel)); // Right Arm

        createJoints();
    }

    private RigidBody createRagdollPart(CollisionShape shape, Vector3f position, Quat4f rotation, float mass, Vector3f initialVel) {
        Transform t = new Transform();
        t.setIdentity();
        t.origin.set(position);
        t.setRotation(rotation);

        Vector3f inertia = new Vector3f();
        shape.calculateLocalInertia(mass, inertia);

        RigidBodyConstructionInfo info = new RigidBodyConstructionInfo(
                mass, new DefaultMotionState(t), shape, inertia
        );
        info.linearDamping = 0.04f;
        info.angularDamping = 0.9f;
        info.restitution = 0.0f;
        info.friction = 0.9f;
        info.additionalDamping = true;

        RigidBody body = new RigidBody(info);

        // Clamp initial velocity before applying
        float speed = initialVel.length();
        if (speed > 8f) {
            initialVel.scale(8f / speed);
        }
        body.setLinearVelocity(initialVel);

        body.setDamping(0.1f, 0.9f);  // Increased linear damping
        body.setSleepingThresholds(0.3f, 0.3f);  // Tighter thresholds

        // More conservative CCD
        body.setCcdMotionThreshold(0.1f);  // Increased from 0.01f
        body.setCcdSweptSphereRadius(0.2f); // Reduced from 0.35f

        world.addRigidBody(body);
        return body;
    }

    private void createJoints() {
        if (ragdollParts.size() < 6) return;

        RigidBody torso = ragdollParts.get(RagdollPart.TORSO.index);
        RigidBody head = ragdollParts.get(RagdollPart.HEAD.index);
        RigidBody lLeg = ragdollParts.get(RagdollPart.LEFT_LEG.index);
        RigidBody rLeg = ragdollParts.get(RagdollPart.RIGHT_LEG.index);
        RigidBody lArm = ragdollParts.get(RagdollPart.LEFT_ARM.index);
        RigidBody rArm = ragdollParts.get(RagdollPart.RIGHT_ARM.index);

        Transform tTorso = new Transform(); torso.getMotionState().getWorldTransform(tTorso);
        Transform tHead  = new Transform(); head.getMotionState().getWorldTransform(tHead);
        Transform tLLeg  = new Transform(); lLeg.getMotionState().getWorldTransform(tLLeg);
        Transform tRLeg  = new Transform(); rLeg.getMotionState().getWorldTransform(tRLeg);
        Transform tLArm  = new Transform(); lArm.getMotionState().getWorldTransform(tLArm);
        Transform tRArm  = new Transform(); rArm.getMotionState().getWorldTransform(tRArm);

        java.util.function.Function<Vector3f, Vector3f> torsoLocalToWorld = (local) -> {
            Quat4f trot = tTorso.getRotation(new Quat4f());
            Vector3f out = rotateVecByQuat(trot, local);
            out.add(tTorso.origin);
            return out;
        };

        // Head <-> Torso
        Vector3f torsoTopWorld = torsoLocalToWorld.apply(new Vector3f(0f, 0.4f, 0f));
        Quat4f hrot = tHead.getRotation(new Quat4f());
        Vector3f headBottomWorld = rotateVecByQuat(hrot, new Vector3f(0f, -0.2f, 0f));
        headBottomWorld.add(tHead.origin);
        Vector3f headAnchor = new Vector3f((torsoTopWorld.x + headBottomWorld.x) * 0.5f,
                (torsoTopWorld.y + headBottomWorld.y) * 0.5f,
                (torsoTopWorld.z + headBottomWorld.z) * 0.5f);
        ragdollJoints.add(createJointAtWorldAnchor(
                torso, head, headAnchor,
                new Vector3f(0,0,0), new Vector3f(0,0,0),
                new Vector3f((float)-Math.toRadians(30), (float)-Math.toRadians(20), (float)-Math.toRadians(30)),
                new Vector3f((float)Math.toRadians(30), (float)Math.toRadians(50), (float)Math.toRadians(30))
        ));

        // Left Leg <-> Torso
        Vector3f torsoLeftHip = torsoLocalToWorld.apply(new Vector3f(-0.1f, -0.55f, 0f));
        Quat4f lrot = tLLeg.getRotation(new Quat4f());
        Vector3f legTopWorld = rotateVecByQuat(lrot, new Vector3f(0f, 0.45f, 0f));
        legTopWorld.add(tLLeg.origin);
        Vector3f lLegAnchor = new Vector3f((torsoLeftHip.x + legTopWorld.x) * 0.5f,
                (torsoLeftHip.y + legTopWorld.y) * 0.5f,
                (torsoLeftHip.z + legTopWorld.z) * 0.5f);
        ragdollJoints.add(createJointAtWorldAnchor(
                torso, lLeg, lLegAnchor,
                new Vector3f(-0.05f,-0.05f,-0.05f), new Vector3f(0.05f,0.05f,0.05f),
                new Vector3f((float)-Math.toRadians(40), 0f, (float)-Math.toRadians(10)),
                new Vector3f((float)Math.toRadians(80), 0f, (float)Math.toRadians(10))
        ));

        // Right Leg <-> Torso
        Vector3f torsoRightHip = torsoLocalToWorld.apply(new Vector3f(0.1f, -0.55f, 0f));
        Quat4f rrot = tRLeg.getRotation(new Quat4f());
        Vector3f rLegTopWorld = rotateVecByQuat(rrot, new Vector3f(0f, 0.45f, 0f));
        rLegTopWorld.add(tRLeg.origin);
        Vector3f rLegAnchor = new Vector3f((torsoRightHip.x + rLegTopWorld.x) * 0.5f,
                (torsoRightHip.y + rLegTopWorld.y) * 0.5f,
                (torsoRightHip.z + rLegTopWorld.z) * 0.5f);
        ragdollJoints.add(createJointAtWorldAnchor(
                torso, rLeg, rLegAnchor,
                new Vector3f(-0.05f,-0.05f,-0.05f), new Vector3f(0.05f,0.05f,0.05f),
                new Vector3f((float)-Math.toRadians(40), 0f, (float)-Math.toRadians(10)),
                new Vector3f((float)Math.toRadians(80), 0f, (float)Math.toRadians(10))
        ));

        // Left Arm <-> Torso
        Vector3f torsoLeftShoulder = torsoLocalToWorld.apply(new Vector3f(-0.35f, 0.05f, 0f));
        Quat4f larot = tLArm.getRotation(new Quat4f());
        Vector3f lArmTopWorld = rotateVecByQuat(larot, new Vector3f(0f, 0.35f, 0f));
        lArmTopWorld.add(tLArm.origin);
        Vector3f lArmAnchor = new Vector3f((torsoLeftShoulder.x + lArmTopWorld.x) * 0.5f,
                (torsoLeftShoulder.y + lArmTopWorld.y) * 0.5f,
                (torsoLeftShoulder.z + lArmTopWorld.z) * 0.5f);
        ragdollJoints.add(createJointAtWorldAnchor(
                torso, lArm, lArmAnchor,
                new Vector3f(-0.02f,-0.02f,-0.02f), new Vector3f(0.02f,0.02f,0.02f),
                new Vector3f((float)-Math.toRadians(80), (float)-Math.toRadians(30), (float)-Math.toRadians(40)),
                new Vector3f((float)Math.toRadians(80), (float)Math.toRadians(30), (float)Math.toRadians(40))
        ));

        // Right Arm <-> Torso
        Vector3f torsoRightShoulder = torsoLocalToWorld.apply(new Vector3f(0.35f, 0.05f, 0f));
        Quat4f rarot = tRArm.getRotation(new Quat4f());
        Vector3f rArmTopWorld = rotateVecByQuat(rarot, new Vector3f(0f, 0.35f, 0f));
        rArmTopWorld.add(tRArm.origin);
        Vector3f rArmAnchor = new Vector3f((torsoRightShoulder.x + rArmTopWorld.x) * 0.5f,
                (torsoRightShoulder.y + rArmTopWorld.y) * 0.5f,
                (torsoRightShoulder.z + rArmTopWorld.z) * 0.5f);
        ragdollJoints.add(createJointAtWorldAnchor(
                torso, rArm, rArmAnchor,
                new Vector3f(-0.02f,-0.02f,-0.02f), new Vector3f(0.02f,0.02f,0.02f),
                new Vector3f((float)-Math.toRadians(80), (float)-Math.toRadians(30), (float)-Math.toRadians(40)),
                new Vector3f((float)Math.toRadians(80), (float)Math.toRadians(30), (float)Math.toRadians(40))
        ));
    }

    private void checkCollisionsForParts() {
        int numManifolds = manager.getDispatcher().getNumManifolds();
        for (int i = 0; i < numManifolds; i++) {
            PersistentManifold manifold = manager.getDispatcher().getManifoldByIndexInternal(i);
            CollisionObject a = (CollisionObject) manifold.getBody0();
            CollisionObject b = (CollisionObject) manifold.getBody1();

            // Skip self-collisions
            if (ragdollParts.contains(a) && ragdollParts.contains(b)) continue;

            for (int p = 0; p < manifold.getNumContacts(); p++) {
                ManifoldPoint pt = manifold.getContactPoint(p);
                if (pt.getDistance() <= 0f) {
                    RigidBody hitPart = null;
                    CollisionObject other = null;

                    if (ragdollParts.contains(a)) { hitPart = (RigidBody) a; other = b; }
                    else if (ragdollParts.contains(b)) { hitPart = (RigidBody) b; other = a; }

                    if (ignoreNext) {
                        ignoreNext = false;
                        return;
                    }

                    if (hitPart != null) {
                        Vector3f contactPoint = new Vector3f();
                        pt.getPositionWorldOnB(contactPoint);
                        float impactSpeed = computeImpactSpeed(a, b, pt);
                        // You can add collision handling here if needed
                    }
                }
            }
        }
    }

    private RagdollPart identifyPart(RigidBody rb) {
        int idx = ragdollParts.indexOf(rb);
        return RagdollPart.byIndex(idx);
    }

    private float computeImpactSpeed(CollisionObject aObj, CollisionObject bObj, ManifoldPoint pt) {
        Vector3f contact = new Vector3f();
        pt.getPositionWorldOnB(contact);

        Vector3f velA = new Vector3f(0f, 0f, 0f);
        Vector3f velB = new Vector3f(0f, 0f, 0f);

        if (pt.getDistance() < -0.1f) {
            return 0f;
        }

        if (aObj instanceof RigidBody) {
            RigidBody a = (RigidBody) aObj;
            a.getLinearVelocity(velA);
            Vector3f angA = new Vector3f();
            a.getAngularVelocity(angA);

            Transform ta = new Transform();
            a.getMotionState().getWorldTransform(ta);
            Vector3f comA = new Vector3f(ta.origin);
            Vector3f rA = new Vector3f();
            rA.sub(contact, comA);

            Vector3f wCrossR = cross(angA, rA);
            velA.add(wCrossR);
        }

        if (bObj instanceof RigidBody) {
            RigidBody b = (RigidBody) bObj;
            b.getLinearVelocity(velB);
            Vector3f angB = new Vector3f();
            b.getAngularVelocity(angB);

            Transform tb = new Transform();
            b.getMotionState().getWorldTransform(tb);
            Vector3f comB = new Vector3f(tb.origin);
            Vector3f rB = new Vector3f();
            rB.sub(contact, comB);

            Vector3f wCrossR = cross(angB, rB);
            velB.add(wCrossR);
        }

        Vector3f rel = new Vector3f();
        rel.sub(velA, velB);
        return rel.length();
    }

    private Vector3f cross(Vector3f a, Vector3f b) {
        Vector3f out = new Vector3f();
        out.x = a.y * b.z - a.z * b.y;
        out.y = a.z * b.x - a.x * b.z;
        out.z = a.x * b.y - a.y * b.x;
        return out;
    }

    private void correctInterpenetrations() {
        for (PersistentManifold manifold : manager.getDispatcher().getInternalManifoldPointer()) {
            int numContacts = manifold.getNumContacts();
            for (int i = 0; i < numContacts; i++) {
                ManifoldPoint point = manifold.getContactPoint(i);
                // Only correct significant penetrations
                if (point.getDistance() < -0.12f) {  // Higher threshold
                    RigidBody a = (RigidBody) manifold.getBody0();
                    RigidBody b = (RigidBody) manifold.getBody1();

                    ignoreNext = true;

                    Vector3f normal = new Vector3f(point.normalWorldOnB);
                    // CRITICAL: Reduced from 4.5x to 1.5x
                    float correctionScale = 1.5f * Math.abs(point.getDistance());
                    normal.scale(correctionScale);

                    // Apply position correction
                    if (a.getInvMass() > 0) a.translate(normal);
                    normal.scale(-1);
                    if (b.getInvMass() > 0) b.translate(normal);

                    // NEW: Dampen velocities after correction
                    if (a.getInvMass() > 0) {
                        Vector3f vel = new Vector3f();
                        a.getLinearVelocity(vel);
                        vel.scale(0.75f);
                        a.setLinearVelocity(vel);

                        Vector3f angVel = new Vector3f();
                        a.getAngularVelocity(angVel);
                        angVel.scale(0.75f);
                        a.setAngularVelocity(angVel);
                    }
                    if (b.getInvMass() > 0) {
                        Vector3f vel = new Vector3f();
                        b.getLinearVelocity(vel);
                        vel.scale(0.75f);
                        b.setLinearVelocity(vel);

                        Vector3f angVel = new Vector3f();
                        b.getAngularVelocity(angVel);
                        angVel.scale(0.75f);
                        b.setAngularVelocity(angVel);
                    }
                }
            }
        }
    }

    private void updateLocalWorldCollision() {
        Vector3f torsoPos = getTorsoPosition();
        BlockPos center = new BlockPos((int)torsoPos.x, (int)torsoPos.y, (int)torsoPos.z);

        if (center.distManhattan(lastCollisionCenter) < 2) return;
        lastCollisionCenter = center;

        for (CollisionObject c : localStaticCollision) world.removeCollisionObject(c);
        localStaticCollision.clear();

        int radius = 3;
        for (int dx=-radius; dx<=radius; dx++)
            for (int dy=-radius; dy<=radius; dy++)
                for (int dz=-radius; dz<=radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = entity.level().getBlockState(pos);
                    if (state.isAir() || state.getFluidState().isSource()) continue;
                    if (isCompletelySurrounded(pos)) continue;

                    VoxelShape shape = state.getCollisionShape(entity.level(), pos);
                    if (shape.isEmpty()) continue;

                    for (AABB box : shape.toAabbs()) {
                        Vector3f halfExtents = new Vector3f(
                                (float)(box.getXsize()/2),
                                (float)(box.getYsize()/2),
                                (float)(box.getZsize()/2)
                        );
                        CollisionShape cs = new BoxShape(halfExtents);

                        Transform t = new Transform();
                        t.setIdentity();
                        t.origin.set(
                                (float)(pos.getX() + box.minX + box.getXsize()/2),
                                (float)(pos.getY() + box.minY + box.getYsize()/2),
                                (float)(pos.getZ() + box.minZ + box.getZsize()/2)
                        );

                        RigidBody rb = new RigidBody(new RigidBodyConstructionInfo(0f, new DefaultMotionState(t), cs, new Vector3f()));
                        rb.setCollisionFlags(rb.getCollisionFlags() | CollisionFlags.STATIC_OBJECT);
                        world.addRigidBody(rb);
                        localStaticCollision.add(rb);
                    }
                }
    }

    private boolean isCompletelySurrounded(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = entity.level().getBlockState(neighbor);
            if (neighborState.isAir() ||
                    neighborState.getFluidState().isSource() ||
                    neighborState.getCollisionShape(entity.level(), neighbor).isEmpty() ||
                    neighborState.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    private void applyFluidForces() {
        BlockPos entityPos = entity.blockPosition();
        if (entity.level().getFluidState(entityPos).isSource()) {
            Vec3 flow = entity.level().getFluidState(entityPos).getFlow(entity.level(), entityPos);
            Vector3f waterFlow = new Vector3f((float) flow.x, (float) flow.y, (float) flow.z);
            waterFlow.scale(5f);

            for (RigidBody body : ragdollParts) {
                Vector3f vel = new Vector3f();
                body.getLinearVelocity(vel);

                Vector3f drag = new Vector3f(vel);
                drag.scale(-2.7f); // water resistance

                Vector3f net = new Vector3f(waterFlow);
                net.add(drag);

                body.applyCentralImpulse(net);
            }
        }
    }

    private RagdollTransform[] getRagdollTransforms() {
        RagdollTransform[] out = new RagdollTransform[ragdollParts.size()];
        for (int i = 0; i < ragdollParts.size(); i++) {
            Transform t = new Transform();
            ragdollParts.get(i).getMotionState().getWorldTransform(t);
            Quat4f rot = t.getRotation(new Quat4f());
            out[i] = new RagdollTransform(i, t.origin.x, t.origin.y, t.origin.z,
                    rot.x, rot.y, rot.z, rot.w);
        }
        return out;
    }

    public void destroy() {
        for (RigidBody r : ragdollParts) {
            world.removeRigidBody(r);
        }
        for (TypedConstraint c : ragdollJoints) {
            world.removeConstraint(c);
        }
        for (CollisionObject co : localStaticCollision) {
            world.removeCollisionObject(co);
        }
        ragdollParts.clear();
        ragdollJoints.clear();
        localStaticCollision.clear();
    }

    // Helper methods
    private Vector3f rotateVecByQuat(Quat4f q, Vector3f v) {
        Vector3f qvec = new Vector3f(q.x, q.y, q.z);
        Vector3f t = new Vector3f();
        t.x = 2f * (qvec.y * v.z - qvec.z * v.y);
        t.y = 2f * (qvec.z * v.x - qvec.x * v.z);
        t.z = 2f * (qvec.x * v.y - qvec.y * v.x);

        Vector3f result = new Vector3f(v);
        Vector3f qwt = new Vector3f(t);
        qwt.scale(q.w);
        result.add(qwt);

        Vector3f cross = new Vector3f();
        cross.x = qvec.y * t.z - qvec.z * t.y;
        cross.y = qvec.z * t.x - qvec.x * t.z;
        cross.z = qvec.x * t.y - qvec.y * t.x;
        result.add(cross);
        return result;
    }

    private Vector3f rotateVecByQuatConjugate(Quat4f q, Vector3f v) {
        Quat4f qc = new Quat4f(-q.x, -q.y, -q.z, q.w);
        return rotateVecByQuat(qc, v);
    }

    private Vector3f worldPointToLocal(Transform bodyWorldTransform, Vector3f worldPoint) {
        Vector3f delta = new Vector3f(worldPoint);
        delta.sub(bodyWorldTransform.origin);
        Quat4f rot = bodyWorldTransform.getRotation(new Quat4f());
        return rotateVecByQuatConjugate(rot, delta);
    }

    private Generic6DofConstraint createJointAtWorldAnchor(RigidBody a, RigidBody b, Vector3f worldAnchor,
                                                           Vector3f linearLower, Vector3f linearUpper,
                                                           Vector3f angularLower, Vector3f angularUpper) {
        Transform ta = new Transform();
        a.getMotionState().getWorldTransform(ta);
        Transform tb = new Transform();
        b.getMotionState().getWorldTransform(tb);

        Vector3f localA_origin = worldPointToLocal(ta, worldAnchor);
        Vector3f localB_origin = worldPointToLocal(tb, worldAnchor);

        Transform localA = new Transform();
        localA.setIdentity();
        localA.origin.set(localA_origin);

        Transform localB = new Transform();
        localB.setIdentity();
        localB.origin.set(localB_origin);

        Generic6DofConstraint joint = new Generic6DofConstraint(a, b, localA, localB, true);
        joint.setLinearLowerLimit(linearLower);
        joint.setLinearUpperLimit(linearUpper);
        joint.setAngularLowerLimit(angularLower);
        joint.setAngularUpperLimit(angularUpper);

        a.activate();
        b.activate();

        world.addConstraint(joint, true);
        return joint;
    }

    public boolean hasBody(CollisionObject obj) {
        return ragdollParts.contains(obj);
    }
}