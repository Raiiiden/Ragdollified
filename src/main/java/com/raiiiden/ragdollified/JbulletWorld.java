package com.raiiiden.ragdollified;

import com.bulletphysics.collision.broadphase.AxisSweep3;
import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.dispatch.CollisionConfiguration;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.constraintsolver.ConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JbulletWorld {

    private static final Map<ServerLevel, JbulletWorld> INSTANCES = new ConcurrentHashMap<>();

    public static JbulletWorld get(ServerLevel level) {
        // One manager per dimension
        return INSTANCES.computeIfAbsent(level, JbulletWorld::new);
    }
    private final ServerLevel level;


    private BroadphaseInterface broadphase;
    private CollisionConfiguration collisionConfig;
    private CollisionDispatcher dispatcher;
    private ConstraintSolver solver;
    private DiscreteDynamicsWorld dynamicsWorld;

    public JbulletWorld(ServerLevel level) {
        this.level = level;
        collisionConfig = new DefaultCollisionConfiguration();
        dispatcher = new CollisionDispatcher(collisionConfig);

        // Large but limited world AABB — still not doing full world collisions
        Vector3f worldAabbMin = new Vector3f(-10000f, -10000f, -10000f);
        Vector3f worldAabbMax = new Vector3f(10000f, 10000f, 10000f);
        broadphase = new AxisSweep3(worldAabbMin, worldAabbMax);

        solver = new SequentialImpulseConstraintSolver();

        dynamicsWorld = new DiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        dynamicsWorld.setGravity(new Vector3f(0f, -9.81f, 0f));
        dynamicsWorld.getSolverInfo().numIterations = 20;
    }

    public void step(float dt) {
        // fixed-step, with substeps
        dynamicsWorld.stepSimulation(dt, 30, 1f/120f);
    }

    public DiscreteDynamicsWorld getDynamicsWorld() {
        return dynamicsWorld;
    }


    public CollisionDispatcher getDispatcher() { return dispatcher; }
    public DiscreteDynamicsWorld getWorld() { return dynamicsWorld; }

}
